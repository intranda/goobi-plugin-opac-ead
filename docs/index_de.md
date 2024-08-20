---
title: EAD Datenübernahme
identifier: intranda_opac_ead
published: true
description: OPAC Plugin für die Datenübernahme von EAD Datensätzen am Beispiel des Universitätsarchives der HU Berlin
---
## Einführung
Die vorliegende Dokumentation beschreibt die Installation, die Konfiguration und den Einsatz einer XML-basierten Datenbank, um damit EAD Dateien zu verwalten und in Goobi zu integrieren.


## Installation und Konfiguration 

### Vorbereitung des EAD-Speichers
Als technische Lösung für die Datenübernahme von EAD-Dateien wurde das Konzept eines EAD-Speichers gewählt. Dabei handelt es sich um eine XML-Datenbank, die wiederholt mit mehreren aktualisierten EAD-Dateien beliefert werden kann und anschließend ähnlich zu einem abfragbaren Katalog als Datenquelle dient, die sowohl von Goobi workflow als auch vom Goobi viewer abgefragt werden kann, um neben Detailinformationen eines Datensatzes ebenfalls Informationen über die Tektonik abfragen zu können.

Durch die Zwischenschaltung dieses EAD-Speichers ist sichergestellt, dass auch die EAD-Dateien jederzeit aktualisiert werden können und die einzelnen Datensätze daraus stets mit aktuellem Kontext angezeigt werden, auch wenn dieser sich seit der ersten Datenübernahme zwischenzeitlich geändert haben sollte.


### Installation der XML-Datenbank BaseX
BaseX ist eine XML-Datenbank, in der die EAD Dateien verwaltet, analysiert und abgefragt werden können. Voraussetzung für die Installation von BaseX ist Java 1.8.

Zunächst muss der Download der Datenbank erfolgen:

[http://basex.org/download/](http://basex.org/download/)

Für die Installation von BaseX auf einem Linux System muss zunächst die zip Datei herunterladen und auf dem Server installiert werden. Dies könnte beispielsweise in diesem Pfad erfolgen:

```bash
/opt/digiverso/basex
```

Anschließend muss die Jetty-Konfiguration angepasst werden, so dass die Applikation nur auf localhost erreichbar ist. Dafür muss in der Konfigurationsdatei `/opt/digiverso/basex/webapp/WEB-INF/jetty.xml` sichergestellt werden, dass der `host` auf `127.0.0.1` steht:

```xml
  <Set name="host">127.0.0.1</Set>
```

Anschließend wird die Systemd Unit File an diesen Pfad installiert:

```bash
/etc/systemd/system/basexhttp.service
```

Diese hat folgenden Aufbau:

```ruby
[Unit]
Description=BaseX HTTP server

[Service]
User=tomcat8
Group=tomcat8
ProtectSystem=full
ExecStart=/opt/digiverso/basex/bin/basexhttp
ExecStop=/opt/digiverso/basex/bin/basexhttp stop

[Install]
WantedBy=multi-user.target
```

Anschließend muss der Daemon neu geladen, die Unit-File aktiviert und die Datenbank neu gestartet werden:

```bash
systemctl daemon-reload
systemctl enable basexhttp.service
systemctl start basexhttp.service
```

Damit das Admin-Interface auch von extern erreichbar ist, kann dieses im `Apache` zum Beispiel mit dem folgenden Abschnitt konfiguriert werden:

```swift
    redirect 301 /basex http://bastel.fritz.box/basex/
    <Location /basex/>
            Require ip 188.40.71.142
            ProxyPass http://localhost:8984/ retry=0
            ProxyPassReverse http://localhost:8984/
    </Location>
```

Im Anschluß daran muss noch das Apache Modul `proxy_http` aktiviert und der Apache neu gestartet werden, damit die Anpassungen wirksam werden:

```xml
a2enmod proxy_http
systemctl restart apache2
```


### Datenbank einrichten
Die XML Datenbank kann nach der Installation unter folgender URL erreicht werden:

[http://localhost:8984/dba/login](http://localhost:8984/dba/login)

Die Zugangsdaten lauten `admin`/`admin`. Nach dem ersten Anmelden sollte daher als erstes ein neues Passwort vergeben werden. Dazu muss der Menüeintrag `Users` geöffnet werden. Hier kann der Accountname angeklickt und das neue Passwort gesetzt werden.

Anschließend kann eine neue Datenbank für die EAD Dateien erzeugt werden. Dazu muss der Menüeintrag `Databases` ausgewählt werden. Mittels `Create` gelangt man in den Dialog dazu. Hier muss einTitel für die Datenbank vergeben werden. Alle anderen Einstellungen können so bleiben.


### Dateien hinzufügen und löschen
Nachdem die Datenbank erstellt wurde, können nun EAD-XML-Dokumente hinzugefügt werden. Dazu kann unter `Databases` die erstellte Datenbank ausgewählt werden. Daraufhin öffnet sich ein Fenster, in dem die zur Datenbank gehörenden Dateien verwaltet werden können. Neue Dateien lassen sich über den Dialog `Add` auswählen und hochladen. Hier kann im Feld `Input` eine EAD-Datei ausgewählt werden. Mittels `Add` wird die Datei hinzugefügt und die Übersichtsseite geladen. Hier können auch Dateien entfernt werden. Dazu müssen sie mittels Checkbox markiert und dann über `Delete` gelöscht werden. Das Aktualisieren einer EAD-Datei ist nur über Löschen und erneutes Hinzufügen möglich.


### Definition der Suchanfrage
Um das Interface zur Abfrage für Goobi einzurichten, muss der Datenbank bekannt gemacht werden, wie eine Anfrage aussieht, was damit geschehen soll und wie das Ergebnis auszusehen hat. Dafür bietet BaseX verschiedene Optionen an. Wir haben uns für [RESTXQ](http://docs.basex.org/wiki/RESTXQ) entschieden, da diese im Gegensatz zur [REST](http://docs.basex.org/wiki/REST) Schnittstelle keine Authentication benötigt.

Dazu muss im Verzeichnis `/opt/digiverso/basex/webapp/` eine neue Datei `eadRequest.xq` erzeugt werden.

```graphql
(: XQuery file to return an ead record :)
module namespace page = 'http://basex.org/examples/web-page';
declare default element namespace "urn:isbn:1-931666-22-9";

declare
  %rest:path("/search/{$identifier}")
  %rest:single
  %rest:GET
function page:getRecord($identifier) {
    let $ead := db:open('CHANGEME')/ead[//c[@level="file"][@id=$identifier]]
    let $record :=$ead//c[@level="file"][@id=$identifier]
    let $header := $ead/eadheader
    return
        <ead>
            {$header}
            {for $c in $record/ancestor-or-self::c
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
};
```

Dieses xquery-Modul wird ausgeführt, wenn Anfragen via `GET` an die Adresse `/search/{$identifier}` gestellt werden. Wenn ein anderer `endpoint` genutzt werden soll, kann dies im Bereich `declare` angepasst werden. Sobald eine Anfrage gestellt wird, wird die Funktion `page:getRecord` ausgeführt. In der ersten Zeile der Funktion muss der zu verwendende Datenbankname definiert werden. Für den Fall, dass die Informationen auf mehrere Datenbanken aufgeteilt wurden, müssen daher auch mehrere Dateien mit dieser Funktion verwendet werden. Dabei muss die Variable `rest:path` eindeutig definiert werden.

Ob die Konfiguration korrekt ist, kann mit einer Anfrage an die Datenbank getestet werden: [http://localhost:8984/search/A91x07542461156845020181205140345849](http://localhost:8984/search/A91x07542461156845020181205140345849)

Änderungen an den Dateien oder den Datenbanken können jederzeit im laufenden Betrieb vorgenommen werden.


### Goobi Anbindung
Nachdem die Datenbank eingerichtet wurde, kann sie in Goobi konfiguriert werden. Da sich die Metadaten deutlich von den bibliographischen Metadaten von Bibliotheken unterscheiden, sollte in Goobi ein eigenes Projekt und ein eigener Regelsatz genutzt werden. Zusätzlich muss das OPAC-Plugin `goobi-plugin-opac-ead` installiert werden.

![Auswahl des Plugins](screen1_de.png)

### Konfiguration der Datei goobi_opac.xml
Die Datei `goobi_opac.xml` muss um zwei weitere Einträge erweitert werden. Zum einen muss der zu verwendende Dokumententyp definiert werden. Dies passiert im Bereich `<doctypes>`:

```xml
<type isContainedWork="false" isMultiVolume="false" isPeriodical="false" rulesetType="SingleRecord" tifHeaderType="Record" title="Record">
    <label language="de">Akte</label>
    <label language="en">Record</label>
    <mapping>SingleRecord</mapping>
</type>
```

In diesem Beispiel wird der Typ Akte (`SingleRecord` im Regelsatz) verwendet.

Außerdem muss die Datenquelle definiert werden:

```xml
<catalogue title="EAD Import">
    <config address="http://localhost:8984/search/" database="hu-ead-example" description="EAD Import" iktlist="IKTLIST-GBV.xml"
            port="80" ucnf="UCNF=NFC&amp;XPNOFF=1" protocol="http://" opacType="ead" />
    <searchFields>
        <searchField  label="Identifier" value="8000" />
    </searchFields>
</catalogue>
```

Das Attribut `title` enthält den Namen, unter dem die Datenquelle in Goobi auswählbar ist. Das Element `<config>` enthält in `address` die URL zur zuvor definierten REST-Schnittstelle und in `database` den Namen der Datenbank.


### Konfiguration des Plugins innerhalb von plugin_opac_ead.xml
Diese Datei ist im Ordner `/opt/digiverso/goobi/config/` zu finden und enthält das Mapping der EAD-Elemente zu Goobi-Metadaten.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<config_plugin>
    <namespaces>
        <namespace prefix="ead" uri="urn:isbn:1-931666-22-9" />
        <namespace prefix="oai" uri="http://www.openarchives.org/OAI/2.0/" />
    </namespaces>

    <documenttype isanchor="false">SingleRecord</documenttype>

<!--    
    <documenttype isanchor="true">VolumeRun</documenttype>
    <documenttype isanchor="false">Record</documenttype>
-->

    <mapping>
        <metadata name="TitleDocMain" xpath="//ead:c[@level='file']/ead:did/ead:unittitle" level="topstruct" xpathType="Element"/>
        <metadata name="CatalogIDDigital" xpath="//ead:c[@level='file']/@id" level="topstruct" xpathType="Attribute"/>
        <metadata name="CatalogIDSource" xpath="//ead:c[@level='file']/@id" level="topstruct" xpathType="Attribute"/>
        <metadata name="Dating" xpath="//ead:c[@level='file']/ead:did/ead:unitdate/@normal" level="topstruct" xpathType="Attribute"/>
        <metadata name="PublicationYear" xpath="//ead:c[@level='file']/ead:did/ead:unitdate" level="topstruct" xpathType="Element"/>
        <metadata name="shelfmarksource" xpath="//ead:c[@level='file']/ead:did/ead:unitid[@type='Signatur']" level="topstruct" xpathType="Element"/>
        <metadata name="AccessLicense" xpath="//ead:c[@level='file']/ead:accessrestrict/ead:p" level="topstruct" xpathType="Element"/>
        <metadata name="TitleDocSub1" xpath="//ead:c[@level='class']/ead:did/ead:unittitle" level="topstruct" xpathType="Element"/>
        <metadata name="Genre" xpath="//ead:c[@level='file']/ead:did/ead:physdesc/ead:genreform" level="topstruct" xpathType="Element"/>
        <metadata name="History" xpath="//ead:c[@level='collection']/ead:scopecontent[/ead:head/text() = 'Bestandsgeschichte']/ead:p" level="topstruct" xpathType="Element"/>
        <metadata name="Description" xpath="//ead:c[@level='collection']/ead:scopecontent[/ead:head/text() = 'Beschreibung']/ead:p" level="topstruct" xpathType="Element"/>
        <metadata name="SizeSourcePrint" xpath="//ead:c[@level='file']/ead:odd/ead:p" level="topstruct" xpathType="Element"/>
        <metadata name="Contains" xpath="//ead:c[@level='file']/ead:did/ead:abstract[@type='Enthält']" level="topstruct" xpathType="Element"/>
        <metadata name="singleDigCollection" xpath="concat(//ead:eadheader/ead:filedesc/ead:titlestmt/ead:titleproper\,'#'\, //ead:c[@level='collection']/ead:did/ead:unitid)" level="topstruct" xpathType="String"/>
    </mapping>
</config_plugin>
```

Im oberen Bereich werden die zur Verfügung stehenden Namespaces definiert, anschließend der zu erzeugende Strukturtyp. Hier kann mit Hilfe des Attributes `isanchor="true/false"` definiert werden, ob ein mehrbändiges Objekt oder ein eigenständiges Objekt erzeugt werden soll. Im Anschluss erfolgt im Bereich `<mapping>` das Mapping der Metadaten. Da in EAD die Unterscheidung zwischen Personen und anderen Metadaten nicht vorgesehen ist, können hier nur normale Metadaten angelegt werden. Jedes `<metadata>` enthält in `name` das Metadatum, so wie es im Regelsatz definiert wurde. In `level` wird angegeben, an welcher Stelle das Metadatum erzeugt werden soll. Mögliche Werte sind `physical`, `topstruct` und `anchor`.

In `xpath` steht der [XPath](https://www.w3.org/TR/1999/REC-xpath-19991116/) Ausdruck, der auf den Datensatz angewendet wird, um den Wert des Metadatums zu ermitteln. Das Attribut `xpathType` beschreibt den Rückgabewert des XPath-Ausdrucks. Dieser kann entweder `Element`, `Attribut` oder `String` sein.


### Projektkonfiguration innerhalb der Datei goobi_projects.xml
Die Datei `goobi_projects.xml` benötigt eine neue Definition für den Publikationstyp und die neuen Metadaten.

```xml
<project name="EAD-Import">
    <createNewProcess>
        <itemlist>

            <!-- Title for Records -->
            <item docstruct="topstruct" from="prozess" isnotdoctype="multivolume" metadata="TitleDocMain" required="true" ughbinding="true"> Titel </item>
            <item docstruct="topstruct" from="prozess" isnotdoctype="multivolume" metadata="TitleDocSub1" required="false" ughbinding="true"> Weitere Titel</item>

            <!-- Identifer -->
            <item docstruct="topstruct" from="prozess" isdoctype="Record" metadata="CatalogIDDigital" required="true" ughbinding="true">Identifier</item>

            <!-- Sammlung -->
            <item docstruct="topstruct" from="prozess" isdoctype="Record" metadata="singleDigCollection" required="true" ughbinding="true">Sammlung</item>

            <item docstruct="topstruct" from="prozess" isdoctype="Record" metadata="Dating" ughbinding="true"> Datierung </item>
            <item docstruct="topstruct" from="prozess" isdoctype="Record" metadata="PublicationYear" ughbinding="true"> Erscheinungsjahr </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="shelfmarksource"> Signatur </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="AccessLicense"> Zugriffsbeschränkung </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="Genre"> Genre </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="History"> Bestandsgeschichte </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="Description"> Beschreibung </item>
            <item from="prozess" isdoctype="Record" ughbinding="true" docstruct="topstruct" metadata="SizeSourcePrint"> Umfang </item>
            <processtitle isdoctype="Record">Identifier</processtitle>
            <hide>collections</hide>
        </itemlist>
        <opac use="true">
            <catalogue>EAD Import</catalogue>
        </opac>
        <templates use="true" />
        <defaultdoctype>Record</defaultdoctype>
        <metadatageneration use="true" />
    </createNewProcess>
    <tifheader>
        <Record>'|[[TYPE]]'+$Doctype+'|[[TITLE]]'+Title+'|[[AUTHORS]]'+Authors+'
            |[[YEAR]]'+Publishing year+'|[[PLACE]]'+Publishing place+'|[[FOLDER]]'+ATS+'_'+Identifier digital (a)+'|'
        </Record>
    </tifheader>
    <dmsImport />
    <validate/>
</project>
```

Nachdem diese Konfiguration abgeschlossen wurde, steht innerhalb von Goobi eine neue Datenquelle innerhalb der Anlegemaske für Vorgänge zur Verfügung. Diese kann nun mittels der Identifier in gleicher Weise abgefragt werden wie andere Datenquellen und Kataloge auch.
