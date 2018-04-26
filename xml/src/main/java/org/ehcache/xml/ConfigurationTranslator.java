/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.xml;

import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.Configuration;
import org.ehcache.config.ResourceType;
import org.ehcache.spi.service.ServiceConfiguration;
import org.ehcache.spi.service.ServiceCreationConfiguration;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.ehcache.core.internal.util.ClassLoading.libraryServiceLoaderFor;

public class ConfigurationTranslator {

  private static final SchemaFactory XSD_SCHEMA_FACTORY = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
  private static final String INDENT_PROPERTY_KEY = "{http://xml.apache.org/xslt}indent-amount";
  private static final String INDENT_SPACING = "2";
  private static final String YES = "yes";

  private static Schema newSchema(Source[] schemas) throws SAXException {
    synchronized (XSD_SCHEMA_FACTORY) {
      return XSD_SCHEMA_FACTORY.newSchema(schemas);
    }
  }

  private static final URL CORE_SCHEMA_URL = XmlConfiguration.class.getResource("/ehcache-core.xsd");

  private final DocumentBuilder domBuilder;
  private final Configuration configuration;

  private final Map<Class<?>, CacheManagerServiceConfigurationParser<?>> cacheManagerServiceConfigurationParserMap;
  private final Map<Class<?>, CacheServiceConfigurationParser<?>> cacheServiceConfigurationParserMap;
  private final Map<ResourceType<?>, CacheResourceConfigurationParser> cacheResourceConfigurationParserMap;

  public ConfigurationTranslator(Configuration configuration) throws IOException, SAXException, ParserConfigurationException {

    this.cacheManagerServiceConfigurationParserMap = new HashMap<>();
    this.cacheServiceConfigurationParserMap = new HashMap<>();
    this.cacheResourceConfigurationParserMap = new HashMap<>();

    this.configuration = Objects.requireNonNull(configuration, "Configuration object cannot be NULL");

    Collection<Source> schemaSources = new ArrayList<>();
    schemaSources.add(new StreamSource(CORE_SCHEMA_URL.openStream()));

    for (CacheManagerServiceConfigurationParser<?> parser : libraryServiceLoaderFor(CacheManagerServiceConfigurationParser.class)) {
      schemaSources.add(parser.getXmlSchema());
      this.cacheManagerServiceConfigurationParserMap.put(parser.getServiceCreationConfigurationType(), parser);
    }
    for (CacheServiceConfigurationParser<?> parser : libraryServiceLoaderFor(CacheServiceConfigurationParser.class)) {
      schemaSources.add(parser.getXmlSchema());
      this.cacheServiceConfigurationParserMap.put(parser.getServiceConfigurationType(), parser);
    }
    // Parsers for /config/cache/resources extensions
    for (CacheResourceConfigurationParser parser : libraryServiceLoaderFor(CacheResourceConfigurationParser.class)) {
      schemaSources.add(parser.getXmlSchema());
      this.cacheResourceConfigurationParserMap.put(parser.getResourceType(), parser);
    }
    this.domBuilder = createAndGetDocumentBuilder(schemaSources);
  }

  public String toXml() {
    StringBuilder sb = new StringBuilder();
    if (configuration.getServiceCreationConfigurations() != null){
      for(ServiceCreationConfiguration<?> serviceCreationConfiguration : configuration.getServiceCreationConfigurations()){
        String xmlPart = getClusterCreationConfigurationXml(serviceCreationConfiguration);
        if(!(xmlPart == null || xmlPart.trim().length()==0)){
          sb.append(xmlPart);
        }
      }
    }
    if (configuration.getCacheConfigurations() != null){
      for(Map.Entry<String, CacheConfiguration<?,?>> cacheConfigurationEntry : configuration.getCacheConfigurations().entrySet()) {
        CacheConfiguration<?,?> cacheConfiguration = cacheConfigurationEntry.getValue();
        Collection<ServiceConfiguration<?>> cacheServiceConfigurations = cacheConfiguration.getServiceConfigurations();
        for(ServiceConfiguration<?> serviceConfiguration : cacheServiceConfigurations){
          String xmlPart = getCacheServiceConfigurationXml(serviceConfiguration);
          if(!(xmlPart == null || xmlPart.trim().length()==0)){
            sb.append(xmlPart);
          }
        }
      }
    }
    return sb.toString();
  }

  protected DocumentBuilder createAndGetDocumentBuilder(Collection<Source> schemaSources) throws SAXException, ParserConfigurationException{
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setIgnoringComments(true);
    factory.setIgnoringElementContentWhitespace(true);
    factory.setSchema(newSchema(schemaSources.toArray(new Source[schemaSources.size()])));
    DocumentBuilder documentBuilder = factory.newDocumentBuilder();
    documentBuilder.setErrorHandler(new ConfigurationParser.FatalErrorHandler());
    return documentBuilder;
  }

  protected String getClusterCreationConfigurationXml(ServiceCreationConfiguration serviceCreationConfig) {
    CacheManagerServiceConfigurationParser<?> serviceConfigurationParser =
      cacheManagerServiceConfigurationParserMap.get(serviceCreationConfig.getServiceType());
    Document doc = domBuilder.newDocument();
    serviceConfigurationParser.translateServiceConfiguration(doc, serviceCreationConfig);
    return getStringFromDocument(doc);
  }

  protected String getCacheServiceConfigurationXml(ServiceConfiguration serviceConfiguration){
    CacheServiceConfigurationParser<?> cacheServiceConfigurationParser =
      this.cacheServiceConfigurationParserMap.get(serviceConfiguration.getServiceType());
    Document doc = domBuilder.newDocument();
    cacheServiceConfigurationParser.translateServiceConfiguration(doc, serviceConfiguration);
    return getStringFromDocument(doc);
  }

  protected String getStringFromDocument(Document doc) {
    try {
      DOMSource domSource = new DOMSource(doc);
      StreamResult result = createAndGetStreamResult();
      Writer writer = result.getWriter();
      Transformer transformer = createAndGetTransformer();
      transformer.transform(domSource, result);
      return writer.toString();
    }
    catch(TransformerException ex) {
      throw new RuntimeException(ex);
    }
  }

  protected StreamResult createAndGetStreamResult(){
    StringWriter writer = new StringWriter();
    StreamResult result = new StreamResult(writer);
    return result;
  }

  protected Transformer createAndGetTransformer() throws TransformerConfigurationException{
    TransformerFactory tf = createAndGetTransformFactory();
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, YES);
    transformer.setOutputProperty(INDENT_PROPERTY_KEY, INDENT_SPACING);
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, YES);
    return  transformer;
  }

  protected TransformerFactory createAndGetTransformFactory(){
    return TransformerFactory.newInstance();
  }
}
