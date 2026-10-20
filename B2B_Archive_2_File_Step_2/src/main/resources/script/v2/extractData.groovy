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

    byte[] raw = message.getBody(byte[].class)
    // ISO-8859-1 maps bytes 1:1 to chars, so binary content survives the round trip
    String text = new String(raw, StandardCharsets.ISO_8859_1)

    // Determine boundary (header Content-Type, fallback: first "--" line of body)
    String boundary = null
    String ct = message.getHeaders().get("Content-Type") as String
    if (ct) {
        def m = ct =~ /(?i)boundary\s*=\s*"?([^";\s]+)"?/
        if (m.find()) boundary = m.group(1)
    }
    if (!boundary) {
        def m = text =~ /(?m)^--([^\r\n]+)\r?$/
        if (m.find()) boundary = m.group(1)
    }
    if (!boundary) {
        throw new IllegalStateException("Multipart boundary not found")
    }

    String delimiter = "--" + boundary
    Map<String, String> ids = new TreeMap<>()
    Map<String, String> values = new HashMap<>()
    byte[] zipBytes = null

    int pos = text.indexOf(delimiter)
    while (pos >= 0) {
        int partStart = pos + delimiter.length()
        if (text.startsWith("--", partStart)) break   // closing delimiter
        if (text.startsWith("\r\n", partStart)) partStart += 2
        else if (text.startsWith("\n", partStart)) partStart += 1

        int next = text.indexOf("\r\n" + delimiter, partStart)
        int partEnd = next >= 0 ? next : text.length()
        int nextPos = next >= 0 ? next + 2 : -1

        String part = text.substring(partStart, partEnd)
        int sep = part.indexOf("\r\n\r\n")
        int sepLen = 4
        if (sep < 0) { sep = part.indexOf("\n\n"); sepLen = 2 }
        if (sep >= 0) {
            String headerBlock = part.substring(0, sep)
            String content = part.substring(sep + sepLen)
            def nm = headerBlock =~ /(?i)Content-Disposition:[^\r\n]*?\bname="([^"]*)"/
            boolean isZip = (headerBlock =~ /(?i)Content-Type:\s*application\/zip/).find()
            if (isZip) {
                zipBytes = content.getBytes(StandardCharsets.ISO_8859_1)
            } else if (nm.find()) {
                String name = nm.group(1)
                if (name == "content") {
                    zipBytes = content.getBytes(StandardCharsets.ISO_8859_1)
                } else {
                    def pm = name =~ /^(propertyId|propertyValue)\[(\d+)\]$/
                    if (pm.matches()) {
                        String value = new String(content.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
                        if (pm.group(1) == "propertyId") ids.put(pm.group(2), value)
                        else values.put(pm.group(2), value)
                    }
                }
            }
        }
        pos = nextPos
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
    String archiveFileName
    if (direction == "IN") {
        archiveDirectory = prop("b2b:senderTradingPartnerName") + "/OUT"
        archiveFileName = prop("b2b:senderMessageType") + "_" + prop("b2b:senderInterchangeControlNumber") + ".edi"
    } else if (direction == "OUT") {
        archiveDirectory = prop("b2b:receiverTradingPartnerName") + "/IN"
        archiveFileName = prop("b2b:receiverMessageType") + "_" + prop("b2b:receiverInterchangeControlNumber") + ".edi"
    } else {
        throw new IllegalStateException("Unexpected b2b:interchangeDirection: '" + direction + "'")
    }
    message.setProperty("ArchiveDirectory", archiveDirectory)
    message.setProperty("ArchiveFileName", archiveFileName)

    if (zipBytes != null) {
        message.setBody(unzip(zipBytes))
    }

    return message
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

