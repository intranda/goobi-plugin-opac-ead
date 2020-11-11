package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.apache.oro.text.perl.Perl5Util;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HttpClientHelper;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class EadOpacPlugin implements IOpacPlugin {

    private List<Namespace> namespaces = null;

    private List<ConfigurationEntry> metadataList = null;

    private String documentType = null;
    private String anchorType = null;

    private XPathFactory xFactory = XPathFactory.instance();

    @Getter
    private PluginType type = PluginType.Opac;

    @Getter
    private String title = "intranda_opac_ead";

    private int hit = 0;

    private Perl5Util perlUtil = new Perl5Util();

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue coc, Prefs prefs) throws Exception {
        // http://basex.org/download/  BaseXxx.zip herunterladen und entpacken
        // in bin/ basexhttp starten
        // http://localhost:8984/dba öffnen (admin/admin)
        // neue DB anlegen, Name "basexdb"
        // Datei eadRecord.xq in webapp Ordner legen

        if (namespaces == null) {
            loadConfiguration();
        }
        if (StringUtils.isNotBlank(inSuchbegriff)) {
            String url = coc.getAddress() + inSuchbegriff;
            String response = HttpClientHelper.getStringFromUrl(url);

            Element element = getRecordFromResponse(response);
            if (element == null) {
                hit = 0;
                return null;
            }
            hit = 1;

            Fileformat mm = new MetsMods(prefs);
            DigitalDocument digitalDocument = new DigitalDocument();
            mm.setDigitalDocument(digitalDocument);

            DocStruct volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(documentType));
            DocStruct anchor = null;
            if (anchorType != null) {
                anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(anchorType));
                anchor.addChild(volume);
                digitalDocument.setLogicalDocStruct(anchor);
            } else {
                digitalDocument.setLogicalDocStruct(volume);
            }
            DocStruct physical = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            digitalDocument.setPhysicalDocStruct(physical);

            for (ConfigurationEntry sp : metadataList) {
                List<String> metadataValues = new ArrayList<>();
                if ("Element".equalsIgnoreCase(sp.getXpathType())) {
                    List<Element> data = xFactory.compile(sp.getXpath(), Filters.element(), null, namespaces).evaluate(element);
                    for (Element e : data) {
                        String value = e.getValue();
                        metadataValues.add(value);
                    }
                } else if ("Attribute".equalsIgnoreCase(sp.getXpathType())) {
                    List<Attribute> data = xFactory.compile(sp.getXpath(), Filters.attribute(), null, namespaces).evaluate(element);
                    for (Attribute a : data) {
                        String value = a.getValue();
                        metadataValues.add(value);
                    }

                } else {
                    List<String> data = xFactory.compile(sp.getXpath(), Filters.fstring(), null, namespaces).evaluate(element);
                    for (String value : data) {
                        metadataValues.add(value);
                    }
                }

                MetadataType mdt = prefs.getMetadataTypeByName(sp.getMetadataName());
                if (mdt == null) {
                    log.error("Cannot initialize metadata type " + sp.getMetadataName());
                } else {

                    for (String value : metadataValues) {
                        if (StringUtils.isNotBlank(sp.getRegularExpression())) {
                            value = perlUtil.substitute(sp.getRegularExpression(), value);
                        }
                        if (StringUtils.isNotBlank(sp.getSearch()) && StringUtils.isNotBlank(sp.getReplace())) {
                            value = value.replace(sp.getSearch(), sp.getReplace().replace("\\u0020", " "));
                        }
                        if (StringUtils.isNotBlank(value)) {
                            try {
                                if (mdt.getIsPerson()) {
                                    Person p = new Person(mdt);
                                    if (value.contains(",")) {
                                        p.setLastname(value.substring(0, value.indexOf(",")).trim());
                                        p.setFirstname(value.substring(value.indexOf(",") + 1).trim());
                                    } else {
                                        p.setLastname(value);
                                    }
                                    if ("physical".equals(sp.getLevel())) {
                                        // add it to phys
                                        physical.addPerson(p);
                                    } else if ("topstruct".equals(sp.getLevel())) {
                                        // add it to topstruct
                                        volume.addPerson(p);
                                    } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                        // add it to anchor
                                        anchor.addPerson(p);
                                    }
                                } else {

                                    Metadata md = new Metadata(mdt);
                                    md.setValue(value);
                                    if ("physical".equals(sp.getLevel())) {
                                        // add it to phys
                                        physical.addMetadata(md);
                                    } else if ("topstruct".equals(sp.getLevel())) {
                                        // add it to topstruct
                                        volume.addMetadata(md);
                                    } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                        // add it to anchor
                                        anchor.addMetadata(md);
                                    }
                                }
                            } catch (Exception e) {
                                log.error(e);
                            }

                        }
                    }
                }
            }
            return mm;

            // ->     http://localhost:8984/search/v8141030

            /*
            <query xmlns="http://basex.org/rest">
            <text>
            <![CDATA[
            declare default element namespace "urn:isbn:1-931666-22-9";
            declare variable $identifier as xs:string external;
            let $ead := db:open('basexdb')/ead[//c[@level="file"][@id=$identifier]]
            let $record :=$ead//c[@level="file"][@id=$identifier]
            let $header := $ead/eadheader
            return
            <ead>
            {$header}
            {
            for $c in $record/ancestor-or-self::c
            return
            <c level="{data($c/@level)}" id="{data($c/@id)}">
            {$c/did}
            {$c/accessrestrict}
            {$c/otherfindaid}
            {$c/odd}
            {$c/scopecontent}
            {$c/index}
            </c>
            }
            </ead>

            ]]>
            </text>
            <variable name="identifier" value="v8141030"/>
            </query>




             */
        }
        return null;
    }

    private void loadConfiguration() {
        namespaces = new ArrayList<>();
        metadataList = new ArrayList<>();
        XMLConfiguration config = ConfigPlugins.getPluginConfig("opac_ead");
        config.setExpressionEngine(new XPathExpressionEngine());
        List<HierarchicalConfiguration> fields = config.configurationsAt("/namespaces/namespace");
        for (HierarchicalConfiguration sub : fields) {
            Namespace namespace = Namespace.getNamespace(sub.getString("@prefix"), sub.getString("@uri"));
            namespaces.add(namespace);
        }

        documentType = config.getString("/documenttype[@isanchor='false']");
        anchorType = config.getString("/documenttype[@isanchor='true']", null);

        fields = config.configurationsAt("mapping/metadata");
        for (HierarchicalConfiguration sub : fields) {
            String metadataName = sub.getString("@name");
            String xpathValue = sub.getString("@xpath");
            String level = sub.getString("@level", "topstruct");
            String xpathType = sub.getString("@xpathType", "Element");

            String regularExpression = sub.getString("@regularExpression");
            String search = sub.getString("@search");
            String replace = sub.getString("@replace");

            ConfigurationEntry entry = new ConfigurationEntry();
            entry.setLevel(level);
            entry.setMetadataName(metadataName);
            entry.setXpath(xpathValue);
            entry.setXpathType(xpathType);
            entry.setRegularExpression(regularExpression);
            entry.setSearch(search);
            entry.setReplace(replace);

            metadataList.add(entry);
        }
    }

    private Element getRecordFromResponse(String response) {
        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document doc = builder.build(new StringReader(response), "utf-8");
            Element oaiRootElement = doc.getRootElement();

            return oaiRootElement;
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

    @Override
    public int getHitcount() {
        return hit;
    }

    @Override
    public String getAtstsl() {
        return null;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        return null;
    }

    @Override
    public String createAtstsl(String value, String value2) {
        return null;
    }

    @Override
    public void setAtstsl(String createAtstsl) {

    }

    @Override
    public String getGattung() {
        return null;
    }

}
