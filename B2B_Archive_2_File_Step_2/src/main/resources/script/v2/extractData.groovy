package script.v2

import com.sap.it.script.v2.api.Message
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/*
 * Input: CMIS createDocument multipart/form-data payload.
 *  - parts propertyId[n] / propertyValue[n] -> message property <propertyId> = <propertyValue>
 *  - part "content" (zip)                   -> unzipped and set as message body
 */
def Message processData(Message message) {

    byte[] raw = message.getBody(byte[].class) ?: new byte[0]
    // ISO-8859-1 maps bytes 1:1 to chars, so binary content survives the round trip
    String text = new String(raw, StandardCharsets.ISO_8859_1)

    Map<String, String> ids = new TreeMap<>()
    Map<String, String> values = new HashMap<>()
    byte[] zipBytes = null

    // One multipart part: name from Content-Disposition, content type, raw bytes
    def handlePart = { String name, String partType, byte[] content ->
        if (partType && partType =~ /(?i)application\/zip/) {
            zipBytes = content
        } else if (name == "content") {
            zipBytes = content
        } else if (name) {
            def pm = name =~ /^(propertyId|propertyValue)\[(\d+)\]$/
            if (pm.matches()) {
                String value = new String(content, StandardCharsets.UTF_8)
                if (pm.group(1) == "propertyId") ids.put(pm.group(2), value)
                else values.put(pm.group(2), value)
            }
        }
    }

    // Determine boundary (header Content-Type, any case, fallback: first "--" line of body)
    String boundary = null
    def ctEntry = message.getHeaders().find { k, v -> k?.toString()?.equalsIgnoreCase("Content-Type") }
    String ct = ctEntry?.value as String
    if (ct) {
        def m = ct =~ /(?i)boundary\s*=\s*"?([^";\s]+)"?/
        if (m.find()) boundary = m.group(1)
    }
    // Only use the header boundary if the body really is that multipart
    if (boundary && !text.contains("--" + boundary)) boundary = null
    if (!boundary) {
        def m = text =~ /(?m)^--([^\r\n]+)\r?$/
        if (m.find()) boundary = m.group(1)
    }

    Map attachments = message.getAttachments() ?: [:]
    List<String> attachmentInfo = []

    if (boundary) {
        // Case 1: raw multipart/form-data in the body (e.g. ProcessDirect or HTTP)
        String delimiter = "--" + boundary
        int pos = text.indexOf(delimiter)
        while (pos >= 0) {
            int partStart = pos + delimiter.length()
            if (text.startsWith("--", partStart)) break   // closing delimiter
            if (text.startsWith("\r\n", partStart)) partStart += 2
            else if (text.startsWith("\n", partStart)) partStart += 1

            // next delimiter at line start; the line break before it (CRLF, or LF only) belongs to the delimiter
            int next = text.indexOf("\n" + delimiter, partStart)
            int partEnd = next >= 0 ? next : text.length()
            if (next > partStart && text.charAt(next - 1) == '\r' as char) partEnd = next - 1
            int nextPos = next >= 0 ? next + 1 : -1

            String part = text.substring(partStart, partEnd)
            int sep = part.indexOf("\r\n\r\n")
            int sepLen = 4
            if (sep < 0) { sep = part.indexOf("\n\n"); sepLen = 2 }
            if (sep >= 0) {
                String headerBlock = part.substring(0, sep)
                byte[] content = part.substring(sep + sepLen).getBytes(StandardCharsets.ISO_8859_1)
                def nm = headerBlock =~ /(?i)Content-Disposition:[^\r\n]*?\bname="([^"]*)"/
                def tm = headerBlock =~ /(?i)Content-Type:\s*([^\r\n;]+)/
                handlePart(nm.find() ? nm.group(1) : null, tm.find() ? tm.group(1) : null, content)
            }
            pos = nextPos
        }
    } else if (!attachments.isEmpty()) {
        // Case 2: the runtime already split the multipart (JMS sender with a stored multipart Content-Type):
        // first part ("cmisaction" = createDocument) is the body, all other parts are attachments
        Map wrappers = [:]
        try { wrappers = message.getAttachmentWrapperObjects() ?: [:] } catch (ignored) { }
        attachments.each { key, dh ->
            String name = key as String
            def w = wrappers.get(key)
            if (w != null) {
                String cd = null
                try { cd = w.getHeader("Content-Disposition") as String } catch (ignored) { }
                def nm = cd ? (cd =~ /(?i)\bname="([^"]*)"/) : null
                if (nm != null && nm.find()) name = nm.group(1)
            }
            String partType = dh.getContentType() as String
            byte[] content = dh.getInputStream().readAllBytes()
            attachmentInfo << (name + " (" + partType + ", " + content.length + " bytes)")
            handlePart(name, partType, content)
        }
    } else {
        // Diagnostics: what actually arrived (header names, Content-Type, body size and start; no property values)
        String start = text.take(120).replaceAll(/[^\x20-\x7E]/, '.')
        throw new IllegalStateException("Multipart boundary not found and no attachments. Content-Type='" + ct +
                "', body " + raw.length + " bytes, body starts '" + start +
                "', headers " + message.getHeaders().keySet().sort())
    }

    Map<String, String> props = new HashMap<>()
    ids.each { idx, propName ->
        String v = values.get(idx) ?: ""
        props.put(propName, v)
        message.setProperty(propName, v)
    }

    // Derive archive directory / file name (directory direction is the opposite of interchangeDirection)
    def prop = { String n -> (props.get(n) ?: "").toString().trim() }
    String direction = prop("b2b:interchangeDirection").toUpperCase()
    String archiveDirectory
    if (direction == "IN") {
        archiveDirectory = prop("b2b:senderTradingPartnerName") + "/OUT"
    } else if (direction == "OUT") {
        archiveDirectory = prop("b2b:receiverTradingPartnerName") + "/IN"
    } else {
        throw new IllegalStateException("Unexpected b2b:interchangeDirection: '" + direction + "'" +
                (attachmentInfo ? ", attachments " + attachmentInfo : ""))
    }
    // File name: <receiverDocumentStandard>_<receiverMessageType>_<receiverInterchangeCreationTime> (no suffix)
    String archiveFileName = prop("b2b:receiverDocumentStandard") + "_" + prop("b2b:receiverMessageType") + "_" +
            formatTimestamp(prop("b2b:receiverInterchangeCreationTime"))
    message.setProperty("ArchiveDirectory", archiveDirectory)
    message.setProperty("ArchiveFileName", archiveFileName)

    if (zipBytes != null) {
        try {
            message.setBody(unzip(zipBytes))
        } catch (Exception e) {
            // e.g. the payload was converted to text on the way (binary bytes replaced by U+FFFD, CR removed)
            int replaced = 0
            for (int i = 0; i + 2 < zipBytes.length; i++) {
                if (zipBytes[i] == (byte) 0xEF && zipBytes[i + 1] == (byte) 0xBF && zipBytes[i + 2] == (byte) 0xBD) replaced++
            }
            throw new IllegalStateException("Zip content (" + zipBytes.length + " bytes) of " + archiveDirectory + "/" +
                    archiveFileName + " cannot be read: " + e.getMessage() +
                    (replaced > 0 ? "; it contains " + replaced + " UTF-8 replacement characters, so the binary payload was converted to text before Step 2" : ""), e)
        }
    }

    return message
}

// CMIS datetime (epoch milliseconds, e.g. 1792666630929; ISO-8601 accepted too) -> "yyyy-MM-dd_HH-mm-ss" (UTC)
private String formatTimestamp(String value) {
    if (!value) return ""
    java.time.Instant instant
    if (value ==~ /-?\d+/) {
        instant = java.time.Instant.ofEpochMilli(Long.parseLong(value))
    } else {
        try {
            instant = java.time.OffsetDateTime.parse(value).toInstant()
        } catch (Exception ignored) {
            return value.replaceAll(/[^A-Za-z0-9._-]/, "-")
        }
    }
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(java.time.ZoneOffset.UTC).format(instant)
}

// Returns the document inside the zip (single file; if several, the first non-json file, else the first file)
private byte[] unzip(byte[] zipBytes) {
    Map<String, byte[]> entries = new LinkedHashMap<>()
    ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))
    try {
        def entry
        while ((entry = zis.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                entries.put(entry.getName(), zis.readAllBytes())
            }
        }
    } finally {
        zis.close()
    }
    if (entries.isEmpty()) {
        throw new IllegalStateException("Zip content is empty")
    }
    def pick = entries.find { k, v -> !k.toLowerCase().endsWith(".json") } ?: entries.entrySet().first()
    return pick.value
}

