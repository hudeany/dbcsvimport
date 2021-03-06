package de.soderer.dbcsvimport.worker;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.stax.StAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import de.soderer.dbcsvimport.DbCsvImportDefinition.DataType;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.DbColumnType;
import de.soderer.utilities.DbColumnType.SimpleDataType;
import de.soderer.utilities.DbUtilities.DbVendor;
import de.soderer.utilities.TextUtilities;
import de.soderer.utilities.Tuple;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.WorkerParentSimple;
import de.soderer.utilities.ZipUtilities;
import de.soderer.utilities.xml.IndentedXMLStreamWriter;

public class DbXmlImportWorker extends AbstractDbImportWorker {
	// Default optional parameters
	private String nullValueText = null;
	
	private InputStream inputStream = null;
	private XMLStreamReader xmlReader = null;
	private Integer itemsAmount = null;
	private Map<String, DbColumnType> dataTypes = null;
	private String itemEntryTagName = null;
	private String listEnclosingTagName = null;
	private String dataPath = null;
	private String schemaFilePath = null;
	
	public DbXmlImportWorker(WorkerParentSimple parent, DbVendor dbVendor, String hostname, String dbName, String username, char[] password, String tableName, boolean isInlineData, String importFilePathOrData) throws Exception {
		super(parent, dbVendor, hostname, dbName, username, password, tableName, isInlineData, importFilePathOrData, DataType.XML);
	}

	public void setNullValueText(String nullValueText) {
		this.nullValueText = nullValueText;
	}

	public void setDataPath(String dataPath) {
		this.dataPath = dataPath;
	}

	public void setSchemaFilePath(String schemaFilePath) {
		this.schemaFilePath = schemaFilePath;
	}

	@Override
	public String getConfigurationLogString() {
		String dataPart;
		if (isInlineData) {
			dataPart = "Data: " + importFilePathOrData + "\n";
		} else {
			dataPart = "File: " + importFilePathOrData + "\n"
			+ "Zip: " + Utilities.endsWithIgnoreCase(importFilePathOrData, ".zip") + "\n";
		}
		return
			dataPart
			+ "Format: XML" + "\n"
			+ "Encoding: " + encoding + "\n"
			+ "CommitOnFullSuccessOnly: " + commitOnFullSuccessOnly + "\n"
			+ "Null value text: " + (nullValueText == null ? "none" : "\"" + nullValueText + "\"") + "\n"
			+ "Table name: " + tableName + "\n"
			+ "Import mode: " + importMode + "\n"
			+ "Duplicate mode: " + duplicateMode + "\n"
			+ "Key columns: " + Utilities.join(keyColumns, ", ") + "\n"
			+ (createTableIfNotExists ? "New table was created: " + tableWasCreated + "\n" : "")
			+ "Mapping: \n" + TextUtilities.addLeadingTab(convertMappingToString(mapping)) + "\n"
			+ "Additional insert values: " + additionalInsertValues + "\n"
			+ "Additional update values: " + additionalUpdateValues + "\n"
			+ "Update with null values: " + updateWithNullValues + "\n";
	}
	
	@Override
	public Map<String, DbColumnType> scanDataPropertyTypes() throws Exception {
		if (dataTypes == null) {
			getAvailableDataPropertyNames();
		}
		
		return dataTypes;
	}

	@Override
	public List<String> getAvailableDataPropertyNames() throws Exception {
		if (dataTypes == null) {
			openReader();
			
			int itemCount = 0;
			dataTypes = new HashMap<String, DbColumnType>();
			
			Map<String, Object> nextItem;
			while ((nextItem = getNextItemData()) != null) {
				for (Entry<String, Object> itemProperty : nextItem.entrySet()) {
					String propertyName = itemProperty.getKey();
					Object propertyValue = itemProperty.getValue();
					
					String formatInfo = null;
					if (mapping != null) {
						for (Tuple<String, String> mappingValue : mapping.values()) {
							if (mappingValue.getFirst().equals(propertyName)) {
								if (formatInfo == null && Utilities.isNotBlank(mappingValue.getSecond())) {
									formatInfo = mappingValue.getSecond();
									break;
								}
							}
						}
					}
					
					SimpleDataType currentType = dataTypes.get(propertyName) == null ? null : dataTypes.get(propertyName).getSimpleDataType();
					if (currentType != SimpleDataType.Blob) {
						if (propertyValue == null) {
							if (!dataTypes.containsKey(propertyName)) {
								dataTypes.put(propertyName, null);
							}
						} else if ("file".equalsIgnoreCase(formatInfo) || (propertyValue instanceof String && ((String) propertyValue).length() > 4000)) {
							dataTypes.put(propertyName, new DbColumnType("BLOB", -1, -1, -1, true, false));
						} else if (currentType != SimpleDataType.String && Utilities.isNotBlank(formatInfo) && !formatInfo.equals(".") && !formatInfo.equals(",") && !formatInfo.equalsIgnoreCase("file") && propertyValue instanceof String) {
							String value = ((String) propertyValue).trim();
							try {
								new SimpleDateFormat(formatInfo).parse(value);
								dataTypes.put(propertyName, new DbColumnType("DATE", -1, -1, -1, true, false));
							} catch (Exception e1) {
								try {
									new SimpleDateFormat(DateUtilities.ISO_8601_DATETIME_FORMAT).parse(value);
									dataTypes.put(propertyName, new DbColumnType("DATE", -1, -1, -1, true, false));
								} catch (Exception e2) {
									dataTypes.put(propertyName, new DbColumnType("VARCHAR", Math.max(dataTypes.get(propertyName) == null ? 0 : dataTypes.get(propertyName).getCharacterLength(), value.getBytes(encoding).length), -1, -1, true, false));
								}
							}
							dataTypes.put(propertyName, new DbColumnType("DATE", -1, -1, -1, true, false));
						} else if (currentType != SimpleDataType.String && currentType != SimpleDataType.Date && currentType != SimpleDataType.Double && propertyValue instanceof Integer) {
							dataTypes.put(propertyName, new DbColumnType("INTEGER", -1, -1, -1, true, false));
						} else if (currentType != SimpleDataType.String && currentType != SimpleDataType.Date && (propertyValue instanceof Float || propertyValue instanceof Double)) {
							dataTypes.put(propertyName, new DbColumnType("DOUBLE", -1, -1, -1, true, false));
						} else {
							dataTypes.put(propertyName, new DbColumnType("VARCHAR", Math.max(dataTypes.get(propertyName) == null ? 0 : dataTypes.get(propertyName).getCharacterLength(), propertyValue.toString().getBytes(encoding).length), -1, -1, true, false));
						}
					}
				}
				
				itemCount++;
			}
			
			itemsAmount = itemCount;
			
			close();
		}
		
		return new ArrayList<String>(dataTypes.keySet());
	}

	@Override
	protected int getItemsAmountToImport() throws Exception {
		if (itemsAmount == null) {
			getAvailableDataPropertyNames();
		}
		
		return itemsAmount;
	}

	private void openReader() throws Exception {
		if (xmlReader != null) {
			throw new Exception("Reader was already opened before");
		}
		
		try {
			if (Utilities.isNotEmpty(schemaFilePath)) {
				InputStream validationStream = null;
				XMLStreamReader xmlStreamReader = null;
				try {
					if (!isInlineData) {
						validationStream = new FileInputStream(new File(importFilePathOrData));
						if (Utilities.endsWithIgnoreCase(importFilePathOrData, ".zip")) {
							validationStream = new ZipInputStream(validationStream);
							((ZipInputStream) validationStream).getNextEntry();
						}
					} else {
						validationStream = new ByteArrayInputStream(importFilePathOrData.getBytes("UTF-8"));
					}
					
					xmlStreamReader = XMLInputFactory.newInstance().createXMLStreamReader(validationStream);
			        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			        Schema schema = factory.newSchema(new File(schemaFilePath));
	
			        Validator validator = schema.newValidator();
			        validator.validate(new StAXSource(xmlStreamReader));
				} catch (Exception e) {
					throw new Exception("XML data does not comply to XSD '" + schemaFilePath + "': " + e.getMessage());
				} finally {
					if (xmlStreamReader != null) {
						xmlStreamReader.close();
					}
					if (validationStream != null) {
						validationStream.close();
					}
				}
			}
			
			if (!isInlineData) {
				inputStream = new FileInputStream(new File(importFilePathOrData));
				if (Utilities.endsWithIgnoreCase(importFilePathOrData, ".zip")) {
					inputStream = new ZipInputStream(inputStream);
					((ZipInputStream) inputStream).getNextEntry();
				}
			} else {
				inputStream = new ByteArrayInputStream(importFilePathOrData.getBytes("UTF-8"));
			}
			
			XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
			xmlReader = xmlInputFactory.createXMLStreamReader(inputStream);
			
			if (Utilities.isNotEmpty(dataPath)) {
				// Read xml path
				if (dataPath.startsWith("/")) {
					dataPath = dataPath.substring(1);
				}
				if (dataPath.endsWith("/")) {
					dataPath = dataPath.substring(0, dataPath.length() - 1);
				}
				Stack<String> currentPath = new Stack<String>();
				xmlReader.nextTag();
				currentPath.push(xmlReader.getLocalName());
				while (currentPath.size() > 0 && !Utilities.join(currentPath, "/").equals(dataPath)) {
					if (XMLStreamConstants.START_ELEMENT == xmlReader.nextTag()) {
						currentPath.push(xmlReader.getLocalName());
					} else {
						currentPath.pop();
					}
				}
				if (currentPath.size() == 0) {
					throw new Exception("Path '" + dataPath + "' is not part of the xml data");
				}
			} else {
				// Read root tag
				xmlReader.nextTag();
			}
			listEnclosingTagName = xmlReader.getLocalName();
			if (!xmlReader.isStartElement()) {
				throw new Exception("Invalid xml data. roottagname: " + xmlReader.getLocalName());
			}
		} catch (Exception e) {
			close();
			throw e;
		}
	}

	@Override
	protected Map<String, Object> getNextItemData() throws Exception {
		if (xmlReader == null) {
			openReader();
		}
		
		// Read item entries
		if (xmlReader.nextTag() > 0) {
			if (xmlReader.isStartElement()) {
				if (itemEntryTagName == null) {
					itemEntryTagName = xmlReader.getLocalName();
				} else if (!itemEntryTagName.equals(xmlReader.getLocalName())) {
					throw new Exception("Invalid xml data. tagname: " + xmlReader.getLocalName());
				}
			} else if (xmlReader.isEndElement()) {
				return null;
			}
			
				
			// Read properties
			Map<String, Object> returnMap = new HashMap<String, Object>();
			while (xmlReader.next() > 0) {
				if (xmlReader.isStartElement()) {
					String propertyName = xmlReader.getLocalName();
					xmlReader.next();
					if (xmlReader.isCharacters()) {
						String value = xmlReader.getText();
						if (nullValueText != null && nullValueText.equals(value)) {
							value = null;
						}
						returnMap.put(propertyName, value);
						
						xmlReader.next();
						if (!xmlReader.isEndElement()) {
							throw new Exception("Invalid xml data. Missing closing tag for: " + propertyName);
						}
					} else if (xmlReader.isEndElement()) {
						returnMap.put(propertyName, "");
					} else {
						throw new Exception("Invalid xml data");
					}
				} else if (xmlReader.isEndElement()) {
					break;
				} else {
					throw new Exception("Invalid xml data");
				}
			}
			return returnMap;
		} else {
			return null;
		}
	}

	@Override
	public void close() {
		if (xmlReader != null) {
			// Caution: XMLStreamReader.close() doesn't close the underlying stream
			Utilities.closeQuietly(xmlReader, inputStream);
			xmlReader = null;
			inputStream = null;
		}
	}

	@Override
	protected File filterDataItems(List<Integer> indexList, String fileSuffix) throws Exception {
		OutputStream outputStream = null;
		XMLStreamWriter xmlWriter = null;
		try {
			openReader();
			
			File filteredDataFile;
			if (Utilities.endsWithIgnoreCase(importFilePathOrData, ".zip")) {
				filteredDataFile = new File(importFilePathOrData + "." + fileSuffix + ".xml.zip");
				outputStream = ZipUtilities.openNewZipOutputStream(filteredDataFile);
				((ZipOutputStream) outputStream).putNextEntry(new ZipEntry(new File(importFilePathOrData + "." + fileSuffix + ".xml").getName()));
			} else {
				filteredDataFile = new File(importFilePathOrData + "." + fileSuffix + ".xml");
				outputStream = new FileOutputStream(filteredDataFile);
			}
			
			xmlWriter = new IndentedXMLStreamWriter(outputStream, encoding, "\t");
			xmlWriter.writeStartDocument("utf-8", "1.0");
			xmlWriter.writeStartElement(listEnclosingTagName);
			
			Map<String, Object> item;
			int itemIndex = 0;
			while ((item = getNextItemData()) != null) {
				itemIndex++;
				if (indexList.contains(itemIndex)) {
					xmlWriter.writeStartElement("entry");
					
					for (Entry<String, Object> entry : item.entrySet()) {
						xmlWriter.writeStartElement(entry.getKey());
						if (entry.getValue() == null) {
							xmlWriter.writeCharacters(nullValueText);
						} else if (entry.getValue() instanceof Date) {
							xmlWriter.writeCharacters(new SimpleDateFormat(DateUtilities.YYYY_MM_DD_HHMMSS).format(entry.getValue()));
						} else if (entry.getValue() instanceof Number) {
							xmlWriter.writeCharacters(entry.getValue().toString());
						} else if (entry.getValue() instanceof String) {
							xmlWriter.writeCharacters((String) entry.getValue());
						} else {
							xmlWriter.writeCharacters(entry.getValue().toString());
						}
						xmlWriter.writeEndElement();
					}
					
					xmlWriter.writeEndElement();
				}
			}
			
			return filteredDataFile;
		} finally {
			close();
			Utilities.closeQuietly(xmlWriter);
			Utilities.closeQuietly(outputStream);
		}
	}
}
