// This is Groovy Flowstep Version 2.x, running with Groovy runtime 4, Downgrade the script if older behaviour needed.

package script.v2

import com.sap.it.script.v2.api.Message

import java.text.SimpleDateFormat

/**
 * Used whenever the caller does not supply a repository id at all: the exchange property
 * "RepositoryId", set by the "Set RepositoryId" content modifier from the externalized
 * parameter {{RepositoryId}}. Falls back to 'CMISFileGateway' if the property is missing.
 */
private String defaultRepositoryId(Message message) {
    Object configured = message?.getProperties()?.get('RepositoryId')
    return isBlank(configured?.toString()) ? 'CMISFileGateway' : configured.toString().trim()
}

/**
 * Mimics a CMIS 1.1 repository so that a CMIS client believes a real CMS sits behind the endpoint.
 * SAP BTP Integration Suite's archiving job (and any test client, e.g. CMIS Workbench) must only ever
 * be pointed at the CMIS **Browser binding** (cmisselector / cmisaction, JSON responses) - this is the
 * only binding this mock answers. There is no AtomPub (or SOAP) fallback/auto-detection any more: a
 * client wrongly configured for AtomPub will simply get Browser-binding (JSON) responses back, making
 * such a configuration mistake immediately obvious instead of silently exercising a second, separately
 * tested code path.
 *
 * (The previously supported AtomPub binding implementation has been moved, disabled but otherwise
 * intact, to the very end of this file - see the "DISABLED - AtomPub binding" banner - in case it is
 * ever needed again. SOAP support was removed outright in an earlier session, see NOTES.md.)
 *
 * Function classifyRequest (use it as "script function" of a first Groovy step) sets the exchange
 * properties createDocument=true / createFolder=true when the request creates a document / folder
 * respectively, so the flow can route only document creations to JMS.
 */
def Message classifyRequest(Message message) {
    Map headers = message.getHeaders()
    String body = asString(message.getBody(java.lang.String))
    Map req = parseRequest(headers, body)
    boolean create = isCreateDocument(req)
    message.setProperty('createDocument', create ? 'true' : 'false')
    message.setProperty('createFolder', isCreateFolder(req) ? 'true' : 'false')
    message.setProperty('createType', isCreateType(req) ? 'true' : 'false')
    message.setProperty('cmis_binding', req.binding)

    // CMIS Browser-binding clients (e.g. OpenCMIS Workbench) routinely send the "objectId" query
    // parameter TWICE in one request (once from a cached base/collection link, once for the actual
    // target). Camel then turns the HTTP header "objectId" into a List, so a direct
    // "${header.objectId}" expression in the iFlow (Data Store lookup key, gateway condition) never
    // matches the single string key the document was stored under -> the lookup always misses
    // (visible as response header "sap_datastoreentryfound: false") and callers get back a synthetic
    // placeholder instead of the real uploaded document. req.params already resolves duplicates the
    // correct way (last value wins, same convention OpenCMIS uses), so expose that single, clean value
    // as its own header for the iFlow's Data Store "get" step and routing gateway to key off instead.
    message.setHeader('CMIS_LookupObjectId', asString(req.params.get('objectId')) ?: '')

    if (create) {
        // Prepare the single-document JSON (property cmis_doc_json) that processData/registerDocument
        // reuse right afterwards so both steps agree on the same generated object id. The document
        // itself is persisted durably by the iFlow's "Write to DS" Data Store step right after this
        // script step runs - this script never stores documents itself (see loadAllDocsFromDataStore()
        // below for where documents are read back from, on every subsequent request).
        try {
            String repositoryId = resolveRepositoryId(headers, body, message)
            // The CMIS Browser Binding createDocument POST targets the containing folder via the
            // "objectId" query parameter (e.g. "...&objectId=c199bcbf-...-ff37" for a document created
            // inside a previously createFolder()'d folder, or "objectId=root" for the repository root) -
            // same convention createFolder uses for its own parentId below. Without capturing this, every
            // document silently ended up with no parentId at all (defaulting to root on read-back), so
            // documents created inside a folder structure lost their place in it.
            String parentId = firstNonBlank(req.params.get('objectId'), rootFolderId())
            Map doc = registerDocument(message, headers, body, repositoryId, createDocumentInfo(req), parentId)
            message.setProperty('cmis_doc_json', toJson(doc))
        } catch (Exception e) {
            // remembering the upload must never prevent the archiving itself
        }
    } else if (isCreateFolder(req)) {
        // Same durability mechanism as createDocument above, reusing the very same cmis_doc_json
        // property/"Write to DS" step - the folder is now actually remembered instead of being faked
        // and forgotten (see handleBrowser()'s former "Folders aren't actually persisted" comment),
        // so the archiving job's dated folder structure (year/month/day) survives across requests.
        try {
            String repositoryId = resolveRepositoryId(headers, body, message)
            String parentId = firstNonBlank(req.params.get('objectId'), rootFolderId())
            String folderName = firstNonBlank(browserDocumentInfo(req).name, 'folder')
            // real SAP DMS: a name is unique within its parent folder -> 409 nameConstraintViolation. The iFlow
            // still runs its "Write to DS" step afterwards, so point cmis_doc_json at the EXISTING entry (same
            // objectId -> the write is an idempotent overwrite) and let processData() answer with the 409.
            Map sibling = docsOfRepository(message, repositoryId).find { Map d ->
                firstNonBlank(asString(d.parentId), rootFolderId()) == parentId && asString(d.documentName) == folderName }
            if (sibling != null) {
                message.setProperty('cmis_conflict_objectId', asString(sibling.objectId))
                message.setProperty('cmis_doc_json', toJson(sibling))
                message.setProperty('cmis_objectId', asString(sibling.objectId))
            } else {
                Map folder = registerFolder(message, headers, repositoryId, folderName, parentId)
                message.setProperty('cmis_doc_json', toJson(folder))
            }
        } catch (Exception e) {
            // remembering the folder must never prevent the archiving itself
        }
    } else if (isCreateType(req)) {
        // createType: persist the submitted type definition in the Data Store like a folder/document
        // (same cmis_doc_json property -> "Write to DS" step; the entry id is the type id). The entry is a
        // pseudo object with objectTypeId 'cmis:type' that docsOfRepository() never lists as a child.
        try {
            Map submitted = parseTypeDefinition(asString(req.params.get('type')))
            if (submitted != null) {
                String repositoryId = resolveRepositoryId(headers, body, message)
                Map typeEntry = [objectId: asString(submitted.id), repositoryId: repositoryId, documentName: asString(submitted.id),
                                 objectTypeId: 'cmis:type', parentId: rootFolderId(),
                                 principal: firstNonBlank(headers.get('SAP_SenderUserId'), 'cpi'),
                                 timestamp: isoTimestamp(new Date()), typeDefinition: toJson(submitted)]
                message.setProperty('cmis_objectId', typeEntry.objectId)
                message.setProperty('cmis_repositoryId', repositoryId)
                message.setProperty('cmis_documentName', typeEntry.documentName)
                message.setProperty('cmis_doc_json', toJson(typeEntry))
            }
        } catch (Exception e) {
            // remembering the type must never prevent the response
        }
    }

    // Defensive: see stripInvalidHttpHeaders() for why bracket-notation CMIS property field names
    // (e.g. "propertyId[0]") must never survive as literal HTTP response header names. respond() (in
    // processData()/handleBrowser()) already does this for every normal response, but this is the very
    // first script step the whole exchange goes through, so clean up here too in case any later step
    // (routing error, etc.) ever causes a response to be emitted before processData() runs.
    stripInvalidHttpHeaders(message)
    return message
}

/** Values the Browser-binding request already contains (name, type, size of the uploaded file). */
private Map createDocumentInfo(Map req) {
    return browserDocumentInfo(req)
}

// ---------------------------------------------------------------------------------------------
// Remembered documents - the BTP Data Store "CMISFileGateway" is the single source of truth. The iFlow
// reads ALL entries at the start of every request (Data Store "Select"/"getAll" operation) into
// property cmis_doc_json_all; this script never caches that list itself (no static/in-memory state),
// it is simply re-read and re-parsed on every single request.
// ---------------------------------------------------------------------------------------------

/**
 * Parses property cmis_doc_json_all (filled by the iFlow from a Data Store "select all entries in
 * CMISFileGateway" step) into the same document Map shape used throughout this script. That property's
 * JSON looks like one of:
 *   {"messages":""}                                                     - store is empty
 *   {"messages":{"message":{"@id":"...","root":{...doc fields...}}}}    - exactly one entry (object, NOT a list!)
 *   {"messages":{"message":[{"@id":"...","root":{...}}, {...}, ...]}}   - two or more entries
 * "root" carries the document fields this script already uses elsewhere (objectId, repositoryId,
 * documentName, mimeType, contentLength, principal, timestamp) - except contentLength arrives here as
 * a JSON string (Data Store/XML round-trip), so it is normalised back into a long.
 */
private List loadAllDocsFromDataStore(Message message) {
    String json = asString(message.getProperties().get('cmis_doc_json_all'))
    if (isBlank(json)) {
        return []
    }
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(json)
    } catch (Exception e) {
        return []
    }
    def messagesNode = (parsed instanceof Map) ? parsed.messages : null
    if (!(messagesNode instanceof Map)) {
        // covers {"messages":""} (empty store) and any other unexpected/blank shape
        return []
    }
    def messageNode = messagesNode.message
    if (messageNode == null) {
        return []
    }
    // a single Data Store entry is serialised as a bare object, not a one-element array
    List entries = (messageNode instanceof List) ? messageNode : [messageNode]
    List docs = []
    entries.each { entry ->
        if (entry instanceof Map && entry.root instanceof Map) {
            Map root = entry.root as Map
            Map restored = [objectId     : asString(root.objectId),
                     repositoryId : asString(root.repositoryId),
                     documentName : asString(root.documentName),
                     mimeType     : asString(root.mimeType),
                     contentLength: normaliseContentLength(root.contentLength),
                     principal    : firstNonBlank(asString(root.principal), 'cpi'),
                     timestamp    : asString(root.timestamp),
                     // Absent on documents/older entries predating the createFolder persistence fix -
                     // default to 'cmis:document' / root so existing behaviour is unchanged for them.
                     objectTypeId : firstNonBlank(asString(root.objectTypeId), 'cmis:document'),
                     parentId     : firstNonBlank(asString(root.parentId), rootFolderId())]
            // custom type definitions (createType) are kept as pseudo entries with objectTypeId 'cmis:type'
            if (!isBlank(asString(root.typeDefinition))) {
                restored.typeDefinition = asString(root.typeDefinition)
            }
            docs << restored
        }
    }
    return docs
}

/** cmis:contentStreamLength round-trips through the Data Store/XML conversion as a JSON string. */
private long normaliseContentLength(Object value) {
    if (value == null) {
        return 0L
    }
    String text = value.toString()
    return text.isLong() ? text.toLong() : 0L
}

/** Documents and folders of the repository - type definition pseudo entries are NOT objects and are excluded. */
private List docsOfRepository(Message message, String repositoryId) {
    return loadAllDocsFromDataStore(message).findAll {
        (it.repositoryId == null || it.repositoryId == repositoryId) && it.objectTypeId != 'cmis:type'
    }
}

/** Custom type definitions (as submitted via createType) of the repository, parsed from the Data Store. */
private List<Map> customTypesOfRepository(Message message, String repositoryId) {
    List<Map> result = []
    loadAllDocsFromDataStore(message).each { Map d ->
        if (d.objectTypeId == 'cmis:type' && (d.repositoryId == null || d.repositoryId == repositoryId)) {
            Map type = parseTypeDefinition(asString(d.typeDefinition))
            if (type != null) {
                result << type
            }
        }
    }
    // a type just created by this very request is not in the Data Store yet
    Map prepared = preparedDoc(message)
    if (prepared != null && prepared.objectTypeId == 'cmis:type') {
        Map type = parseTypeDefinition(asString(prepared.typeDefinition))
        if (type != null && !result.any { it.id == type.id }) {
            result << type
        }
    }
    return result
}

private Map findCustomType(Message message, String repositoryId, String typeId) {
    return customTypesOfRepository(message, repositoryId).find { it.id == typeId }
}

private Map parseTypeDefinition(String json) {
    if (isBlank(json)) {
        return null
    }
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(json)
        return (parsed instanceof Map && parsed.id) ? new LinkedHashMap(parsed) : null
    } catch (Exception e) {
        return null
    }
}

/**
 * Type definition of a custom type created via createType: the submitted definition, completed with the
 * properties inherited from its (document) base type, like a real repository reports it.
 */
private Map customTypeJson(Map submitted, boolean withPropertyDefinitions = true) {
    String typeId = asString(submitted.id)
    Map type = new LinkedHashMap(submitted)
    type.baseId = baseIdFor(asString(submitted.baseId) ?: typeId)
    type.parentId = firstNonBlank(submitted.parentId, type.baseId)
    if (!withPropertyDefinitions) {
        type.remove('propertyDefinitions')
        return type
    }
    Map propDefs = [:]
    propertyDefinitionList(asString(type.baseId)).each { List d ->
        String id = d[0] as String
        propDefs[id] = [id: id, localName: id, localNamespace: 'http://docs.oasis-open.org/ns/cmis/core/200908/',
                        displayName: id, queryName: id, description: id, propertyType: d[1], cardinality: d[2],
                        updatability: d[3], inherited: true, required: d[4], queryable: false, orderable: false]
    }
    if (submitted.propertyDefinitions instanceof Map) {
        (submitted.propertyDefinitions as Map).each { k, v -> propDefs[k as String] = v }
    }
    type.propertyDefinitions = propDefs
    return type
}

private Map findDoc(Message message, String repositoryId, String objectId) {
    // document restored from the data store into property cmis_doc_json (GET with objectId) or just created
    Map restored = preparedDoc(message)
    if (restored != null && restored.objectId == objectId) {
        return restored
    }
    return docsOfRepository(message, repositoryId).find { it.objectId == objectId }
}

/** The document prepared by classifyRequest for the current upload (null if there is none). */
private Map preparedDoc(Message message) {
    String json = asString(message.getProperties().get('cmis_doc_json'))
    if (isBlank(json)) {
        return null
    }
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(json)
        return parsed instanceof Map ? new LinkedHashMap(parsed) : null
    } catch (Exception e) {
        return null
    }
}

def Message processData(Message message) {

    // CPI sees the internal http URL; switch only the scheme to https (keep host and path) so links
    // in the response do not trigger a 301 redirect at the Cloud Foundry router
    String camelUrl = message.getHeaders().get('CamelHttpUrl')
    if (camelUrl != null) {
        message.setHeader('CamelHttpUrl', camelUrl.replaceFirst('^(?i)http://', 'https://'))
    }

    def headers = message.getHeaders()
    def properties = message.getProperties()

    logRequestToMpl(message, headers)

    String inboundBody = asString(properties.get('inbound_body'))
    if (isBlank(inboundBody)) {
        inboundBody = asString(message.getBody(java.lang.String))
    }

    Map req = parseRequest(headers, inboundBody)
    String repositoryId = resolveRepositoryId(headers, inboundBody, message)
    String baseUrl = resolveBaseUrl(headers)
    message.setProperty('cmis_binding', req.binding)
    logBindingToMpl(message, req)

    // Browser binding is the only one this mock answers any more (see the class-level doc comment) -
    // there is no AtomPub/SOAP fallback dispatch here any longer.
    return handleBrowser(message, req, repositoryId, baseUrl)
}


/** Adds the detected binding and the reason for it as custom header properties (searchable in MPL monitoring). */
private void logBindingToMpl(Message message, Map req) {
    try {
        def messageLog = messageLogFactory?.getMessageLog(message)
        if (messageLog != null) {
            messageLog.addCustomHeaderProperty('CMIS_Binding', String.valueOf(req.binding))
            messageLog.addCustomHeaderProperty('CMIS_Binding_Reason', String.valueOf(req.reason))
        }
    } catch (Exception e) {
        // intentionally ignored
    }
}

/**
 * Attaches the incoming headers (as JSON) and, if present, the incoming payload to the MPL.
 * Logging must never break the CMIS response, so failures are swallowed.
 */
private void logRequestToMpl(Message message, Map headers) {
    try {
        def messageLog = messageLogFactory?.getMessageLog(message)
        if (messageLog == null) {
            return
        }

        Map sanitized = new TreeMap()
        headers.each { k, v ->
            String name = String.valueOf(k)
            boolean secret = name.equalsIgnoreCase('Authorization') || name.equalsIgnoreCase('Cookie')
            sanitized.put(name, secret ? '***' : (v == null ? null : v.toString()))
        }
        messageLog.addAttachmentAsString('Incoming Headers',
                groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(sanitized)), 'application/json')

        String payload = asString(message.getBody(java.lang.String))
        if (!isBlank(payload)) {
            String mediaType = payload.trim().startsWith('<') ? 'application/xml' : 'text/plain'
            messageLog.addAttachmentAsString('Incoming Payload', payload, mediaType)
        }
    } catch (Exception e) {
        // intentionally ignored
    }
}

/** Registers a faked document and returns its facts; "info" carries values already extracted by the binding handlers. */
private Map registerDocument(Message message, Map headers, String inboundBody, String repositoryId, Map info, String parentId = rootFolderId()) {
    Map doc = preparedDoc(message)   // already created by classifyRequest -> same object id as the remembered one
    if (doc == null) {
        String objectId = UUID.randomUUID().toString()
        String documentName = firstNonBlank(info.name, resolveDocumentName(inboundBody, headers, objectId))
        String mimeType = firstNonBlank(info.mimeType, resolveMimeType(inboundBody, headers))
        long contentLength = info.length != null ? (info.length as long) : resolveContentLength(inboundBody, headers)
        doc = [objectId: objectId, repositoryId: repositoryId, documentName: documentName, mimeType: mimeType,
               contentLength: contentLength, principal: firstNonBlank(headers.get('SAP_SenderUserId'), 'cpi'),
               timestamp: isoTimestamp(new Date()), parentId: firstNonBlank(parentId, rootFolderId())]
    }

    message.setProperty('cmis_objectId', doc.objectId)
    message.setProperty('cmis_repositoryId', doc.repositoryId ?: repositoryId)
    message.setProperty('cmis_documentName', doc.documentName)
    return doc
}

/**
 * Registers a faked folder and returns its facts; mirrors registerDocument() above so classifyRequest()
 * can persist it the same way (property cmis_doc_json -> the iFlow's "Write to DS" Data Store step),
 * letting the archiving job's dated folder structure (year/month/day, each level created via its own
 * createFolder call against its parent) actually survive across requests instead of being thrown away
 * right after the fake "201 Created" response, as it used to be.
 */
private Map registerFolder(Message message, Map headers, String repositoryId, String folderName, String parentId) {
    Map folder = preparedDoc(message)   // already created by classifyRequest -> same object id as the remembered one
    if (folder == null) {
        folder = [objectId: UUID.randomUUID().toString(), repositoryId: repositoryId, documentName: folderName,
                  objectTypeId: 'cmis:folder', parentId: firstNonBlank(parentId, rootFolderId()),
                  principal: firstNonBlank(headers.get('SAP_SenderUserId'), 'cpi'), timestamp: isoTimestamp(new Date())]
    }

    message.setProperty('cmis_objectId', folder.objectId)
    message.setProperty('cmis_repositoryId', folder.repositoryId ?: repositoryId)
    message.setProperty('cmis_documentName', folder.documentName)
    return folder
}

private Message respond(Message message, int status, String contentType, String body, String repositoryId) {
    message.setHeader('CamelHttpResponseCode', status)
    message.setHeader('Content-Type', contentType)
    message.setHeader('RepositoryID', repositoryId)
    message.setBody(body)
    stripInvalidHttpHeaders(message)
    return message
}

/**
 * Camel's HTTP/Servlet consumer exposes EVERY parsed request parameter - the query string AND, for an
 * "application/x-www-form-urlencoded" POST body, the body too (standard servlet behaviour:
 * HttpServletRequest#getParameterMap() merges both) - as exchange IN headers automatically, before this
 * script ever runs. By default Camel then copies IN headers through to OUT, so these end up as HTTP
 * *response* headers as well.
 *
 * The CMIS 1.1 Browser Binding spec represents multi-valued/indexed properties with bracket-index
 * field names - "propertyId[0]", "propertyValue[0]", "propertyId[1]", ... (see createFolder/
 * createDocument traffic: "propertyId[0]=cmis:name&propertyValue[0]=2026&..."). "[" and "]" are NOT
 * legal characters in an HTTP header field-name (RFC 7230 token grammar) - when the runtime actually
 * writes such a header to the wire, the result is a malformed HTTP response. SAP BTP's Cloud Foundry
 * router then can't parse it and returns "502 Bad Gateway: ... malformed MIME header line:
 * \"propertyId[0]: cmis:name\"" to the client - even though the iFlow/this script itself processed the
 * request and produced a perfectly correct body (visible as a normal, successful MPL entry with correct
 * input/output - the corruption happens only when translating the OUT message's headers to real HTTP
 * response header lines, a step that happens after the script/MPL logging already completed).
 *
 * Called from respond() (every response goes through it) so no header with an illegal name ever
 * survives into the final HTTP response, regardless of binding/selector/action.
 */
private void stripInvalidHttpHeaders(Message message) {
    try {
        Map headers = message.getHeaders()
        List bad = headers.keySet().findAll { k -> k == null || !(k.toString() ==~ /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/) }
        // headers.remove(key) alone does NOT actually delete the header on SAP CPI's Message wrapper -
        // getHeaders() returns a map that isn't wired back for removal that way (confirmed empirically:
        // MPL still showed "propertyId[0]" etc. in the outgoing headers even after this ran). CPI's
        // documented way to actually remove a header is message.setHeader(name, null) - passing null is
        // the API's specific deletion signal, not a plain Map.remove(). Do both for safety/future-proofing.
        bad.each { String k ->
            headers.remove(k)
            message.setHeader(k, null)
        }
    } catch (Exception e) {
        // header cleanup is defensive only - must never break the response itself
    }
}


private String rootFolderId() {
    return 'root'
}

private String repositoryName(String repositoryId) {
    return "SRB Archive (${repositoryId})"
}


private Map parseQuery(String query) {
    Map result = [:]
    if (isBlank(query)) {
        return result
    }
    String q = query.contains('?') ? query.substring(query.indexOf('?') + 1) : query
    q.split('&').each { String pair ->
        if (pair) {
            int idx = pair.indexOf('=')
            String k = urlDecode(idx < 0 ? pair : pair.substring(0, idx))
            String v = idx < 0 ? '' : urlDecode(pair.substring(idx + 1))
            result.put(k, v)
        }
    }
    return result
}


/**
 * Tries to echo back the cmis:name the caller sent, so the response matches the request.
 */
private String resolveDocumentName(String inboundBody, Map headers, String objectId) {
    String fromBody = extractCmisProperty(inboundBody, 'cmis:name')
    if (!isBlank(fromBody)) {
        return fromBody
    }
    String fromAtomTitle = extractFirstGroup(inboundBody, /<(?:\w+:)?title[^>]*>([^<]*)<\/(?:\w+:)?title>/)
    if (!isBlank(fromAtomTitle)) {
        return fromAtomTitle
    }
    String fileName = firstNonBlank(headers.get('CamelFileName'), headers.get('FileName'))
    if (!isBlank(fileName)) {
        return fileName
    }
    return "document-${objectId}"
}

private String resolveMimeType(String inboundBody, Map headers) {
    String fromBody = extractCmisProperty(inboundBody, 'cmis:contentStreamMimeType')
    if (!isBlank(fromBody)) {
        return fromBody
    }
    // AtomPub createDocument carries the type of the uploaded file in <cmisra:content><cmisra:mediatype>
    String fromContent = extractFirstGroup(inboundBody, /<(?:\w+:)?mediatype[^>]*>([^<]+)<\/(?:\w+:)?mediatype>/)
    if (!isBlank(fromContent)) {
        return fromContent
    }
    String contentType = asString(headers.get('Content-Type'))
    if (!isBlank(contentType) && !contentType.toLowerCase().contains('atom+xml')) {
        return contentType.split(';')[0].trim()
    }
    return 'application/octet-stream'
}

/** Size of the decoded <cmisra:base64> content, computed from the encoded length (the payload can be large, so no regex over it). */
private long base64ContentLength(String body) {
    if (isBlank(body)) {
        return -1L
    }
    def open = (body =~ /<(?:\w+:)?base64[^>]*>/)
    if (!open.find()) {
        return -1L
    }
    int start = open.end()
    int end = body.indexOf('</', start)
    if (end < 0) {
        return -1L
    }
    int encoded = 0
    int padding = 0
    for (int i = start; i < end; i++) {
        char c = body.charAt(i)
        if (!Character.isWhitespace(c)) {
            encoded++
            padding = (c == ('=' as char)) ? padding + 1 : 0
        }
    }
    return (long) (encoded / 4) * 3 - padding
}

private long resolveContentLength(String inboundBody, Map headers) {
    String declared = extractCmisProperty(inboundBody, 'cmis:contentStreamLength')
    if (!isBlank(declared) && declared.isLong()) {
        return declared.toLong()
    }
    long fromBase64 = base64ContentLength(inboundBody)
    if (fromBase64 >= 0) {
        return fromBase64
    }
    String contentLengthHeader = asString(headers.get('Content-Length'))
    if (!isBlank(contentLengthHeader) && contentLengthHeader.isLong()) {
        return contentLengthHeader.toLong()
    }
    return inboundBody == null ? 0L : inboundBody.getBytes('UTF-8').length
}

private String extractCmisProperty(String body, String propertyDefinitionId) {
    if (isBlank(body)) {
        return null
    }
    def pattern = ~/(?s)propertyDefinitionId\s*=\s*["']${java.util.regex.Pattern.quote(propertyDefinitionId)}["'].*?<(?:\w+:)?value[^>]*>(.*?)<\/(?:\w+:)?value>/
    def matcher = pattern.matcher(body)
    return matcher.find() ? matcher.group(1).trim() : null
}

private String extractFirstGroup(String body, String regex) {
    if (isBlank(body)) {
        return null
    }
    def matcher = (body =~ regex)
    return matcher.find() ? matcher.group(1).trim() : null
}

/**
 * Rebuilds the endpoint URL the caller used, so links in the response point back here.
 */
private String resolveBaseUrl(Map headers) {
    String url = firstNonBlank(headers.get('CamelHttpUrl'), headers.get('CamelHttpUri'))
    if (!isBlank(url)) {
        url = url.contains('?') ? url.substring(0, url.indexOf('?')) : url
        // CPI sees the internal http URL; advertising it makes clients get a 301 redirect to https
        return url.replaceFirst('^(?i)http://', 'https://')
    }
    return 'https://localhost/http/archive/2/smb'
}

private String isoTimestamp(Date date) {
    def formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    return formatter.format(date)
}

/**
 * Determines the repository id the caller addressed. The sender may not send the
 * RepositoryID header at all, so several sources are probed before falling back to
 * defaultRepositoryId() (exchange property "RepositoryId"):
 *   1. any HTTP header named "repositoryid" (case-insensitive)
 *   2. the CMIS "repositoryId" URL query parameter
 *   3. a cmis:repositoryId / repositoryId element in the request body
 * Falls back to defaultRepositoryId() if none of them yields a value.
 */
private String resolveRepositoryId(Map headers, String inboundBody, Message message) {
    String fromHeader = headerIgnoreCase(headers, 'repositoryid')
    if (!isBlank(fromHeader)) {
        return fromHeader.trim()
    }

    String query = firstNonBlank(headers.get('CamelHttpQuery'), headers.get('CamelHttpUri'), headers.get('CamelHttpUrl'))
    String fromQuery = extractFirstGroup(query, /(?i)(?:^|[?&])repositoryId=([^&#]+)/)
    if (!isBlank(fromQuery)) {
        return urlDecode(fromQuery)
    }

    String fromBody = extractFirstGroup(inboundBody, /(?i)<(?:\w+:)?repositoryId[^>]*>([^<]+)<\/(?:\w+:)?repositoryId>/)
    if (!isBlank(fromBody)) {
        return fromBody.trim()
    }


    return defaultRepositoryId(message)
}

private String headerIgnoreCase(Map headers, String name) {
    if (headers == null) {
        return null
    }
    def entry = headers.find { k, v -> k != null && k.toString().equalsIgnoreCase(name) }
    return entry == null ? null : asString(entry.value)
}

private String urlEncode(String value) {
    return URLEncoder.encode(value == null ? '' : value, 'UTF-8')
}

private String urlDecode(String value) {
    if (value == null) {
        return null
    }
    try {
        return URLDecoder.decode(value, 'UTF-8')
    } catch (Exception e) {
        return value
    }
}

private String firstNonBlank(Object... candidates) {
    for (Object candidate : candidates) {
        String value = asString(candidate)
        if (!isBlank(value)) {
            return value
        }
    }
    return null
}

private String asString(Object value) {
    return value == null ? null : value.toString()
}

private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty()
}

private String escapeXml(String value) {
    if (value == null) {
        return ''
    }
    return value.replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&apos;')
}


/**
 * CMIS "baseId" is a closed enumeration (cmis:document / cmis:folder / cmis:relationship / cmis:policy /
 * cmis:item / cmis:secondary) identifying which base type a type derives from - it must NEVER be the
 * custom type's own id. Any custom/secondary type this mock is asked about (e.g. "b2b:document_v2") is
 * treated as a document subtype unless it is literally "cmis:folder".
 */
private boolean isFolderType(String typeId) {
    return typeId == 'cmis:folder'
}

private String baseIdFor(String typeId) {
    return isFolderType(typeId) ? 'cmis:folder' : 'cmis:document'
}

/** Custom (non-base) types must advertise their parent type id; the two CMIS base types have none. */
private String parentIdFor(String typeId) {
    return (typeId == 'cmis:document' || typeId == 'cmis:folder') ? null : baseIdFor(typeId)
}

private String displayNameFor(String typeId) {
    if (typeId == 'cmis:folder') {
        return 'Folder'
    }
    if (typeId == 'cmis:document') {
        return 'Document'
    }
    // e.g. "b2b:document_v2" -> "document_v2"
    int idx = typeId.indexOf(':')
    return idx >= 0 ? typeId.substring(idx + 1) : typeId
}


/**
 * Property definitions of the base types: [id, propertyType, cardinality, updatability, required].
 * A CMIS client (OpenCMIS) rejects a type without property definitions.
 */
private List propertyDefinitionList(String typeId) {
    boolean folder = typeId == 'cmis:folder'
    List defs = [
        ['cmis:name', 'string', 'single', 'readwrite', true],
        ['cmis:description', 'string', 'single', 'readwrite', false],
        ['cmis:objectId', 'id', 'single', 'readonly', true],
        ['cmis:baseTypeId', 'id', 'single', 'readonly', true],
        ['cmis:objectTypeId', 'id', 'single', 'oncreate', true],
        ['cmis:secondaryObjectTypeIds', 'id', 'multi', 'readwrite', false],
        ['cmis:createdBy', 'string', 'single', 'readonly', false],
        ['cmis:creationDate', 'datetime', 'single', 'readonly', false],
        ['cmis:lastModifiedBy', 'string', 'single', 'readonly', false],
        ['cmis:lastModificationDate', 'datetime', 'single', 'readonly', false],
        ['cmis:changeToken', 'string', 'single', 'readonly', false]
    ]
    if (folder) {
        defs << ['cmis:parentId', 'id', 'single', 'readonly', false]
        defs << ['cmis:path', 'string', 'single', 'readonly', false]
        defs << ['cmis:allowedChildObjectTypeIds', 'id', 'multi', 'readonly', false]
        // returned by folderPropsMap() like the real SAP DMS does - OpenCMIS clients reject undeclared properties
        defs << ['sap:owner', 'string', 'single', 'readonly', false]
        defs << ['sap:parentIds', 'id', 'multi', 'readonly', false]
    } else {
        defs << ['cmis:isImmutable', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:isLatestVersion', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:isMajorVersion', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:isLatestMajorVersion', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:isPrivateWorkingCopy', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:versionLabel', 'string', 'single', 'readonly', false]
        defs << ['cmis:versionSeriesId', 'id', 'single', 'readonly', false]
        defs << ['cmis:isVersionSeriesCheckedOut', 'boolean', 'single', 'readonly', false]
        defs << ['cmis:versionSeriesCheckedOutBy', 'string', 'single', 'readonly', false]
        defs << ['cmis:versionSeriesCheckedOutId', 'id', 'single', 'readonly', false]
        defs << ['cmis:checkinComment', 'string', 'single', 'readonly', false]
        defs << ['cmis:contentStreamLength', 'integer', 'single', 'readonly', false]
        defs << ['cmis:contentStreamMimeType', 'string', 'single', 'readonly', false]
        defs << ['cmis:contentStreamFileName', 'string', 'single', 'readonly', false]
        defs << ['cmis:contentStreamId', 'id', 'single', 'readonly', false]
        // custom SAP extension property (modelled on the real SAP Document Management Service); must be
        // declared here or OpenCMIS clients (e.g. CMIS Workbench) reject it with "Cannot convert property
        // 'sap:parentIds' because it does not exist in the object type."
        defs << ['sap:parentIds', 'id', 'multi', 'readonly', false]
    }
    return defs
}

// ---------------------------------------------------------------------------------------------
// Request classification
// ---------------------------------------------------------------------------------------------

private Map parseRequest(Map headers, String body) {
    String method = firstNonBlank(headers.get('CamelHttpMethod'), 'POST').toUpperCase()
    Map params = parseQuery(asString(headers.get('CamelHttpQuery')))
    String contentType = headerIgnoreCase(headers, 'Content-Type') ?: ''
    String lowerType = contentType.toLowerCase()

    // Browser binding is the ONLY binding this mock answers (see the class-level doc comment) - there
    // is no AtomPub/SOAP detection or fallback any more, so "binding"/"reason" are informational only
    // (still surfaced via logBindingToMpl() / the cmis_binding exchange property for MPL monitoring).
    Map req = [method: method, params: params, binding: 'BROWSER', contentType: contentType, body: body,
               fields: [:], file: null,
               reason: 'Browser binding (only binding supported)']

    if (method == 'POST' && lowerType.contains('application/x-www-form-urlencoded')) {
        params.putAll(parseQuery(body))
    } else if (method == 'POST' && lowerType.contains('multipart/form-data')) {
        parseMultipart(body, contentType, req)
        params.putAll(req.fields.findAll { k, v -> !k.startsWith('property') })
    }

    return req
}


private boolean isCreateDocument(Map req) {
    return req.params.get('cmisaction') == 'createDocument'
}

private boolean isCreateFolder(Map req) {
    return req.params.get('cmisaction') == 'createFolder'
}

private boolean isCreateType(Map req) {
    return req.params.get('cmisaction') == 'createType'
}

private void parseMultipart(String body, String contentType, Map req) {
    String boundary = extractFirstGroup(contentType, /(?i)boundary="?([^";]+)"?/)
    if (isBlank(body) || isBlank(boundary)) {
        return
    }
    body.split('--' + java.util.regex.Pattern.quote(boundary)).each { String part ->
        int sep = part.indexOf('\r\n\r\n')
        int sepLen = 4
        if (sep < 0) {
            sep = part.indexOf('\n\n')
            sepLen = 2
        }
        if (sep < 0) {
            return
        }
        String partHeaders = part.substring(0, sep)
        String partBody = part.substring(sep + sepLen).replaceFirst(/(\r?\n)$/, '')
        String name = extractFirstGroup(partHeaders, /(?i)Content-Disposition:[^\r\n]*?\bname="([^"]*)"/)
        if (name == null) {
            return
        }
        // the OpenCMIS client sends the file name unquoted (filename=abc.zip), other clients quote it
        String fileName = extractFirstGroup(partHeaders, /(?i)Content-Disposition:[^\r\n]*?\bfilename="?([^";\r\n]*)"?/)
        if (fileName != null) {
            req.file = [name: fileName,
                        mimeType: extractFirstGroup(partHeaders, /(?i)Content-Type:\s*([^\r\n;]+)/),
                        length: partBody.getBytes('ISO-8859-1').length]
        } else {
            req.fields.put(name, partBody)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Browser binding (JSON)
// ---------------------------------------------------------------------------------------------

private Message handleBrowser(Message message, Map req, String repositoryId, String baseUrl) {
    Map params = req.params
    String json = 'application/json;charset=UTF-8'
    String action = params.get('cmisaction')
    String selector = params.get('cmisselector')
    boolean succinct = params.get('succinct') == 'true'
    String objectId = firstNonBlank(params.get('objectId'), rootFolderId())

    if (req.method == 'POST') {
        switch (action) {
            case 'createDocument':
                Map info = browserDocumentInfo(req)
                Map doc = registerDocument(message, message.getHeaders(), req.body, repositoryId, info, objectId)
                message.setHeader('CMISObjectId', doc.objectId)
                return respond(message, 201, json, toJson(objectJson(propsMapFor(doc), succinct)), repositoryId)
            case 'createFolder':
                // Persisted the same way createDocument is - see registerFolder()/classifyRequest()'s
                // cmis_doc_json property, saved durably by the iFlow's "Write to DS" Data Store step
                // right after this script runs - so the archiving job's dated folder structure
                // (year/month/day, each level created via its own createFolder call against its parent)
                // now actually survives across requests instead of being faked and forgotten.
                String folderName = firstNonBlank(browserDocumentInfo(req).name, 'folder')
                String conflictId = asString(message.getProperties().get('cmis_conflict_objectId'))
                if (!isBlank(conflictId)) {
                    return respond(message, 409, json, toJson([exception: 'nameConstraintViolation',
                            message: "Child  ${folderName} with Id ${conflictId} already exists".toString()]), repositoryId)
                }
                Map folder = registerFolder(message, message.getHeaders(), repositoryId, folderName, objectId)
                message.setHeader('CMISObjectId', folder.objectId)
                return respond(message, 201, json, toJson(objectJson(propsMapWithPath(message, repositoryId, folder), succinct)), repositoryId)
            case 'createType':
                // CMIS 1.1 createType: body field "type" holds the JSON type definition; the response is
                // the created type definition (201). Persisted by classifyRequest() -> "Write to DS".
                Map submittedType = parseTypeDefinition(asString(params.get('type')))
                if (submittedType == null) {
                    return respond(message, 400, json, toJson([exception: 'invalidArgument', message: 'Missing or invalid type definition']), repositoryId)
                }
                return respond(message, 201, json, toJson(customTypeJson(submittedType)), repositoryId)
            case 'deleteObject':
                // Actual removal from the Data Store (the single source of truth for listings) must
                // happen in the iFlow - this script has no way to delete a Data Store entry itself.
                message.setProperty('cmis_delete_objectId', objectId)
                return respond(message, 200, json, '', repositoryId)
            default:
                return respond(message, 200, json, toJson(objectJson(objectProps(objectId), succinct)), repositoryId)
        }
    }

    if (isBlank(selector) && isBlank(params.get('objectId'))) {
        return respond(message, 200, json, toJson([(repositoryId): repositoryInfoJson(repositoryId, baseUrl)]), repositoryId)
    }

    switch (selector) {
        case 'repositoryInfo':
            return respond(message, 200, json, toJson(repositoryInfoJson(repositoryId, baseUrl)), repositoryId)
        case 'typeDefinition':
            // base types plus the custom types created via createType (e.g. b2b:document_v2)
            String requestedType = firstNonBlank(params.get('typeId'), 'cmis:document')
            if (!(requestedType in ['cmis:document', 'cmis:folder'])) {
                Map customType = findCustomType(message, repositoryId, requestedType)
                if (customType != null) {
                    return respond(message, 200, json, toJson(customTypeJson(customType)), repositoryId)
                }
                return respond(message, 404, json, toJson([exception: 'objectNotFound',
                        message: "Type with ID ${requestedType} for repository ${repositoryId} not found".toString()]), repositoryId)
            }
            return respond(message, 200, json, toJson(typeJson(requestedType)), repositoryId)
        case 'typeChildren':
            // real SAP DMS: {"types":[...],"hasMoreItems":false}, property definitions only on request
            boolean childDefs = params.get('includePropertyDefinitions') == 'true'
            String childParent = params.get('typeId')
            List customTypes = customTypesOfRepository(message, repositoryId)
            List childTypes
            if (isBlank(childParent)) {
                childTypes = [typeJson('cmis:document', childDefs), typeJson('cmis:folder', childDefs)]
            } else if (childParent == 'cmis:document') {
                childTypes = customTypes.collect { Map t -> customTypeJson(t, childDefs) }
            } else {
                childTypes = []
            }
            return respond(message, 200, json, toJson([types: childTypes, hasMoreItems: false]), repositoryId)
        case 'typeDescendants':
            // real SAP DMS: a JSON array of {type, children}; custom types hang below cmis:document
            boolean descDefs = params.get('includePropertyDefinitions') == 'true'
            List customDescendants = customTypesOfRepository(message, repositoryId).collect { Map t ->
                [type: customTypeJson(t, descDefs), children: []] }
            String descParent = params.get('typeId')
            List typeTree
            if (isBlank(descParent)) {
                typeTree = [[type: typeJson('cmis:document', descDefs), children: customDescendants],
                            [type: typeJson('cmis:folder', descDefs), children: []]]
            } else if (descParent == 'cmis:document') {
                typeTree = customDescendants
            } else {
                typeTree = []
            }
            return respond(message, 200, json, toJson(typeTree), repositoryId)
        case 'children':
            // real SAP DMS: {"objects":[{"object":{succinctProperties,exactACL}}],"hasMoreItems":false} - no
            // numItems, but the CMIS TCK's Object Compliance Check flags that with a WARNING ("getChildren
            // did not report the total number of items!"), so add it too (optional per spec, cheap to include).
            // Filtered by parentId (not just "only under root") now that createFolder() persists folders
            // with their real parentId - lets the archiving job's dated folder structure (year/month/day)
            // actually be navigated level by level instead of only ever showing root's direct children.
            List childDocs = docsOfRepository(message, repositoryId).findAll { d -> firstNonBlank(asString(d.parentId), rootFolderId()) == objectId }
            int skip = (params.get('skipCount') ?: '0').toString().isInteger() ? Math.max(0, (params.get('skipCount') ?: '0').toString().toInteger()) : 0
            int max = (params.get('maxItems') ?: '').toString().isInteger() ? params.get('maxItems').toInteger() : 0
            List page = childDocs.drop(skip)
            if (max > 0) {
                page = page.take(max)
            }
            boolean withSegment = params.get('includePathSegment') == 'true'
            List listed = page.collect { Map d ->
                Map entry = [object: objectJson(applyFilter(propsMapWithPath(message, repositoryId, d), params.get('filter')), succinct, params)]
                if (withSegment) {
                    entry.pathSegment = d.documentName
                }
                entry
            }
            return respond(message, 200, json, toJson([objects: listed, hasMoreItems: (skip + page.size()) < childDocs.size(),
                                                         numItems: childDocs.size()]), repositoryId)
        case 'descendants':
            // real SAP DMS: a JSON array of {object, children}
            return respond(message, 200, json, toJson([]), repositoryId)
        case 'checkedout':
            return respond(message, 200, json, toJson([objects: [], hasMoreItems: false]), repositoryId)
        case 'content':
            return respond(message, 404, json, toJson([exception: 'objectNotFound', message: 'Content not available']), repositoryId)
        case 'versions':
            // real SAP DMS: a JSON array of objects, one entry per version
            Map versioned = findDoc(message, repositoryId, objectId)
            Map versionProps = applyFilter(versioned != null ? propsMapFor(versioned) : objectProps(objectId), params.get('filter'))
            return respond(message, 200, json, toJson([objectJson(versionProps, succinct, params)]), repositoryId)
        case 'allowableActions':
            // CMIS Browser binding spec: the response body IS the allowableActions object itself,
            // not wrapped in an envelope - OpenCMIS's ObjectServiceImpl.getAllowableActions() parses
            // the raw JSON straight into an AllowableActions instance. Missing this selector entirely
            // (it previously fell through to the generic "not supported" default) is exactly what the
            // CMIS TCK's "Object Compliance Check" flags when it calls getAllowableActions().
            // real SAP DMS answers per object type: folders get the folder set (create/children/...), documents the document set
            Map aaTarget = findDoc(message, repositoryId, objectId)
            boolean aaFolder = objectId == rootFolderId() || (aaTarget != null && asString(aaTarget.objectTypeId) == 'cmis:folder')
            return respond(message, 200, json, toJson(allowableActionsJson(aaFolder, objectId == rootFolderId())), repositoryId)
        case 'parent':
            // real SAP DMS: the parent folder as a single object (folders only)
            Map child = findDoc(message, repositoryId, objectId)
            if (child == null && objectId != rootFolderId()) {
                return respond(message, 404, json, toJson([exception: 'objectNotFound', message: 'Object not found']), repositoryId)
            }
            if (child == null || asString(child.objectTypeId) != 'cmis:folder') {
                return respond(message, 400, json, toJson([exception: 'invalidArgument', message: 'The object has no single parent folder']), repositoryId)
            }
            Map folderParent = parentFolderProps(message, repositoryId, child)
            if (folderParent == null) {
                return respond(message, 404, json, toJson([exception: 'objectNotFound', message: 'Parent folder not found']), repositoryId)
            }
            return respond(message, 200, json, toJson(objectJson(applyFilter(folderParent, params.get('filter')), succinct, params)), repositoryId)
        case 'parents':
            // real SAP DMS: [{"object":{parent folder, cmis:path "/2026/10"},"relativePathSegment":"<name>"}]
            // - this is what CMIS Workbench uses to render the "Paths" of a document
            Map member = findDoc(message, repositoryId, objectId)
            if (member == null) {
                return objectId == rootFolderId() ? respond(message, 200, json, '[]', repositoryId) :
                        respond(message, 404, json, toJson([exception: 'objectNotFound', message: 'Object not found']), repositoryId)
            }
            Map parentProps = parentFolderProps(message, repositoryId, member)
            if (parentProps == null) {
                return respond(message, 200, json, '[]', repositoryId)
            }
            Map parentEntry = [object: objectJson(applyFilter(parentProps, params.get('filter')), succinct, params)]
            if (params.get('includeRelativePathSegment') == 'true') {
                parentEntry.relativePathSegment = member.documentName
            }
            return respond(message, 200, json, toJson([parentEntry]), repositoryId)
        case ['object', 'properties', null, '']:
            Map known = findDoc(message, repositoryId, objectId)
            if (known == null && objectId != rootFolderId()) {
                // real SAP DMS: {"exception":"objectNotFound","message":"<objectId>"} - the archiving job probes ids
                return respond(message, 404, json, toJson([exception: 'objectNotFound', message: objectId]), repositoryId)
            }
            Map objectProperties = applyFilter(known != null ? propsMapWithPath(message, repositoryId, known) : objectProps(objectId), params.get('filter'))
            return respond(message, 200, json, toJson(objectJson(objectProperties, succinct, params)), repositoryId)
        default:
            return respond(message, 400, json, toJson([exception: 'notSupported', message: "cmisselector '${selector}' not supported"]), repositoryId)
    }
}

private Map browserDocumentInfo(Map req) {
    // propertyId[i]/propertyValue[i] pairs arrive in req.fields for a multipart/form-data POST
    // (createDocument, which also carries the uploaded file as a separate part), but in req.params
    // for a plain application/x-www-form-urlencoded POST (createFolder has no file to upload, so the
    // archiving job sends it this simpler way - see parseRequest()). Check both so e.g. the
    // requested folder name ("2026") isn't lost and silently replaced by the "folder" fallback below.
    Map fields = req.fields
    Map params = req.params
    String name = null
    for (int i = 0; i < 100; i++) {
        String idKey = "propertyId[${i}]"
        String propId = fields.containsKey(idKey) ? fields.get(idKey) : params.get(idKey)
        if (propId == null) {
            break
        }
        if (propId == 'cmis:name') {
            String valKey = "propertyValue[${i}]"
            name = fields.containsKey(valKey) ? fields.get(valKey) : params.get(valKey)
        }
    }
    Map file = req.file
    return [name: firstNonBlank(name, file?.name), mimeType: file?.mimeType, length: file?.length]
}

private Map objectProps(String objectId) {
    return objectId == rootFolderId() ? folderPropsMap() :
            documentPropsMap([objectId: objectId, documentName: "document-${objectId}", mimeType: 'application/pdf',
                              contentLength: 0L, principal: 'cpi', timestamp: isoTimestamp(new Date())])
}

/**
 * Property map: id -> [type, value]; date values are epoch millis as the JSON binding requires. Every
 * property declared in propertyDefinitionList('cmis:folder') must be a key here (value null/empty is
 * fine) - the CMIS TCK's Object Compliance Check fails with "Property is not included in response!" for
 * any declared property that is missing entirely when the caller asks for filter=* (e.g.
 * cmis:description, cmis:secondaryObjectTypeIds, cmis:parentId, cmis:allowedChildObjectTypeIds).
 * Defaults describe the single real folder (root); createFolder() below reuses this with a freshly
 * generated id/name/parentId to fake a plausible (but unpersisted) new folder object.
 */
private Map folderPropsMap(String objectId = rootFolderId(), String name = 'root', String path = '/', String parentId = null) {
    long now = System.currentTimeMillis()
    // real SAP DMS adds sap:owner to every folder and sap:parentIds to every non-root folder
    Map sapProps = ['sap:owner': ['string', 'system']]
    if (parentId != null) {
        sapProps.put('sap:parentIds', ['id', [parentId]])
    }
    return sapProps + ['cmis:objectId': ['id', objectId], 'cmis:baseTypeId': ['id', 'cmis:folder'],
            'cmis:objectTypeId': ['id', 'cmis:folder'], 'cmis:name': ['string', name],
            'cmis:description': ['string', null], 'cmis:secondaryObjectTypeIds': ['id', []],
            'cmis:path': ['string', path], 'cmis:parentId': ['id', parentId],
            // Includes 'cmis:folder' (not just 'cmis:document'): SAP BTP's archiving job builds a
            // PathPrefix-based dated folder structure (year/month/day, e.g. "2026" -> "10" -> "10") -
            // each level is itself a folder created *inside* another folder, so a spec-compliant client
            // that actually checks this property before attempting the next createFolder call needs to
            // see that a folder may contain child folders too, not just documents.
            'cmis:allowedChildObjectTypeIds': ['id', ['cmis:document', 'cmis:folder']],
            'cmis:createdBy': ['string', 'system'], 'cmis:lastModifiedBy': ['string', 'system'],
            'cmis:creationDate': ['datetime', now], 'cmis:lastModificationDate': ['datetime', now],
            'cmis:changeToken': ['string', '0']]
}

private long epochMillis(Object iso) {
    try {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").parse(iso.toString()).getTime()
    } catch (Exception e) {
        return System.currentTimeMillis()
    }
}

/**
 * Picks the right property-map builder for a document/folder entry restored from the Data Store (via
 * loadAllDocsFromDataStore()/docsOfRepository()/findDoc()) or just freshly created by registerFolder().
 * Dispatches on objectTypeId, which defaults to 'cmis:document' for entries predating the createFolder
 * persistence fix (see loadAllDocsFromDataStore()), so existing document entries keep rendering exactly
 * as before.
 */
private Map propsMapFor(Map entry, String path = null) {
    if (asString(entry.objectTypeId) == 'cmis:folder') {
        String name = asString(entry.documentName)
        return folderPropsMap(asString(entry.objectId), name, path ?: "/${name}".toString(), asString(entry.parentId))
    }
    return documentPropsMap(entry)
}

/** Like propsMapFor(), but a folder's cmis:path is the full path from root ("/2026/10"), as SAP DMS reports it. */
private Map propsMapWithPath(Message message, String repositoryId, Map entry) {
    if (asString(entry.objectTypeId) == 'cmis:folder') {
        return propsMapFor(entry, folderPath(message, repositoryId, entry))
    }
    return propsMapFor(entry)
}

/** Builds "/year/month/..." by walking the parentId chain of a stored folder up to the root folder. */
private String folderPath(Message message, String repositoryId, Map folder) {
    List all = docsOfRepository(message, repositoryId)
    Map prepared = preparedDoc(message)
    List<String> segments = []
    Set<String> seen = [] as Set
    Map current = folder
    while (current != null && asString(current.objectId) != rootFolderId() && seen.add(asString(current.objectId))) {
        segments.add(0, asString(current.documentName))
        String parentId = firstNonBlank(asString(current.parentId), rootFolderId())
        if (parentId == rootFolderId()) {
            break
        }
        current = all.find { it.objectId == parentId } ?: (prepared != null && prepared.objectId == parentId ? prepared : null)
    }
    return '/' + segments.join('/')
}

/** Properties of the folder that contains the given object (root folder properties for top level objects). */
private Map parentFolderProps(Message message, String repositoryId, Map entry) {
    String parentId = firstNonBlank(asString(entry.parentId), rootFolderId())
    if (parentId == rootFolderId()) {
        return folderPropsMap()
    }
    Map parent = findDoc(message, repositoryId, parentId)
    return parent != null ? propsMapWithPath(message, repositoryId, parent) : null
}

private Map documentPropsMap(Map doc) {
    long ts = epochMillis(doc.timestamp)
    return ['cmis:objectId': ['id', doc.objectId], 'cmis:baseTypeId': ['id', 'cmis:document'],
            'cmis:objectTypeId': ['id', 'cmis:document'], 'cmis:name': ['string', doc.documentName],
            'cmis:description': ['string', "Archived via SAP Integration Suite (repository ${doc.repositoryId})".toString()],
            'cmis:secondaryObjectTypeIds': ['id', []],
            'cmis:createdBy': ['string', doc.principal], 'cmis:lastModifiedBy': ['string', doc.principal],
            'cmis:creationDate': ['datetime', ts], 'cmis:lastModificationDate': ['datetime', ts],
            'cmis:changeToken': ['string', '0'],
            'cmis:versionSeriesId': ['id', doc.objectId], 'cmis:versionLabel': ['string', '1.0'],
            'cmis:isLatestVersion': ['boolean', true], 'cmis:isMajorVersion': ['boolean', true],
            'cmis:isLatestMajorVersion': ['boolean', true], 'cmis:isVersionSeriesCheckedOut': ['boolean', false],
            'cmis:versionSeriesCheckedOutBy': ['string', null], 'cmis:versionSeriesCheckedOutId': ['id', null],
            'cmis:checkinComment': ['string', null],
            'cmis:isPrivateWorkingCopy': ['boolean', false],
            // doc.contentLength may arrive as a String (round-tripped through the Data Store/XML) or as a
            // Number (freshly created) depending on the caller; normalise it here unconditionally so the
            // Browser-binding JSON always emits an unquoted number for this "integer" typed property -
            // otherwise OpenCMIS clients (e.g. CMIS Workbench) fail with "Invalid property value: <length>".
            'cmis:isImmutable': ['boolean', false], 'cmis:contentStreamLength': ['integer', normaliseContentLength(doc.contentLength)],
            'cmis:contentStreamMimeType': ['string', doc.mimeType], 'cmis:contentStreamFileName': ['string', doc.documentName],
            'cmis:contentStreamId': ['id', "${doc.objectId}-content"],
            // doc.parentId is absent on entries predating this fix (defaults to root, same as
            // loadAllDocsFromDataStore()'s own fallback) - real parentId otherwise (see registerDocument()).
            'sap:parentIds': ['id', [firstNonBlank(asString(doc.parentId), rootFolderId())]]]
}

/** Honours the CMIS "filter" parameter (comma separated property ids); objectId and objectTypeId are always kept. */
private Map applyFilter(Map props, String filter) {
    if (isBlank(filter) || filter.trim() == '*') {
        return props
    }
    Set wanted = filter.split(',').collect { it.trim() } as Set
    wanted << 'cmis:objectId'
    wanted << 'cmis:objectTypeId'
    return props.findAll { k, v -> wanted.contains(k) }
}

/**
 * Allowable actions as SAP DMS reports them (compared via mitmdump): the root folder cannot be deleted/moved
 * and has no parent folder, every other folder (e.g. /2026/10) can be deleted (also as tree), moved and has a
 * parent folder; documents get the document set.
 */
private Map allowableActionsJson(boolean folder, boolean root = false) {
    boolean subFolder = folder && !root
    Map actions = [canDeleteObject: !folder || subFolder, canUpdateProperties: folder, canGetFolderTree: folder, canGetProperties: true,
                   canGetObjectRelationships: false, canGetObjectParents: true, canGetFolderParent: subFolder,
                   canGetDescendants: folder, canMoveObject: !folder || subFolder, canDeleteContentStream: false, canCheckOut: !folder,
                   canCancelCheckOut: false, canCheckIn: false, canSetContentStream: false, canGetAllVersions: !folder,
                   canAddObjectToFolder: false, canRemoveObjectFromFolder: false, canGetContentStream: !folder,
                   canApplyPolicy: false, canGetAppliedPolicies: false, canRemovePolicy: false, canGetChildren: folder,
                   canCreateDocument: folder, canCreateFolder: folder, canCreateRelationship: false, canCreateItem: false,
                   canDeleteTree: subFolder, canGetRenditions: false, canGetACL: true, canApplyACL: true]
    return actions
}

/** Object in Browser binding format; allowableActions / acl are added only when the request asks for them (like SAP DMS). */
private Map objectJson(Map props, boolean succinct, Map params = [:]) {
    Map result
    if (succinct) {
        result = [succinctProperties: props.collectEntries { k, v -> [(k): v[1]] }]
    } else {
        result = [properties: props.collectEntries { k, v ->
            [(k): [id: k, localName: k, displayName: k, queryName: k, type: v[0], cardinality: (v[1] instanceof List ? 'multi' : 'single'), value: v[1]]]
        }]
    }
    if (params.get('includeAllowableActions') == 'true') {
        result.allowableActions = allowableActionsJson(props.get('cmis:baseTypeId')?.getAt(1) == 'cmis:folder',
                props.get('cmis:objectId')?.getAt(1) == rootFolderId())
    }
    if (params.get('includeACL') == 'true') {
        result.acl = [aces: [[principal: [principalId: '{sap:builtin}everyone'], permissions: ['cmis:all'], isDirect: true]], isExact: true]
    }
    result.exactACL = false
    return result
}

private Map repositoryInfoJson(String repositoryId, String baseUrl) {
    String repoUrl = "${baseUrl}?repositoryId=${urlEncode(repositoryId)}"
    return [repositoryId: repositoryId, repositoryName: repositoryName(repositoryId),
            repositoryDescription: 'Archive repository exposed via SAP Integration Suite',
            vendorName: 'SRB Consulting', productName: 'SRB Archive Gateway', productVersion: '1.0',
            rootFolderId: rootFolderId(), cmisVersionSupported: '1.1', repositoryUrl: repoUrl,
            rootFolderUrl: "${repoUrl}&objectId=${rootFolderId()}".toString(), changesIncomplete: true,
            principalIdAnonymous: 'anonymous', principalIdAnyone: 'anyone',
            capabilities: [capabilityACL: 'none', capabilityAllVersionsSearchable: false, capabilityChanges: 'none',
                           capabilityContentStreamUpdatability: 'anytime', capabilityGetDescendants: false,
                           capabilityGetFolderTree: false, capabilityMultifiling: false, capabilityPWCSearchable: false,
                           capabilityPWCUpdatable: false, capabilityQuery: 'none', capabilityRenditions: 'none',
                           capabilityUnfiling: false, capabilityVersionSpecificFiling: false, capabilityJoin: 'none']]
}

private Map typeJson(String typeId, boolean withPropertyDefinitions = true) {
    boolean folder = isFolderType(typeId)
    // baseId MUST be a base-type enum value (cmis:document/cmis:folder/...), never the (possibly custom)
    // typeId itself - see baseIdFor(). A client requesting a custom type like "b2b:document_v2" would
    // otherwise receive an invalid baseId and fail to parse/validate the type definition.
    // "creatable" is always true, even for cmis:folder (see the matching AtomPub/SOAP comment on
    // typeInner()'s <cmis:creatable> for why: archiving jobs check this during INITIALIZATION before
    // deciding whether to even attempt creating the PathPrefix dated-folder structure).
    Map type = [id: typeId, localName: typeId, localNamespace: 'http://docs.oasis-open.org/ns/cmis/core/200908/',
                displayName: displayNameFor(typeId), queryName: typeId, baseId: baseIdFor(typeId),
                parentId: parentIdFor(typeId), creatable: true,
                fileable: true, queryable: false, fulltextIndexed: false, includedInSupertypeQuery: true,
                controllablePolicy: false, controllableACL: false]
    if (!folder) {
        type.versionable = false
        type.contentStreamAllowed = 'required'
    }
    if (!withPropertyDefinitions) {
        return type
    }
    Map propDefs = [:]
    propertyDefinitionList(typeId).each { List d ->
        String id = d[0] as String
        propDefs[id] = [id: id, localName: id, localNamespace: 'http://docs.oasis-open.org/ns/cmis/core/200908/',
                        displayName: id, queryName: id, description: id, propertyType: d[1], cardinality: d[2],
                        updatability: d[3], inherited: false, required: d[4], queryable: false, orderable: false]
    }
    type.propertyDefinitions = propDefs
    return type
}

private String toJson(Object value) {
    return groovy.json.JsonOutput.toJson(value)
}

// =================================================================================================
// DISABLED - AtomPub binding (kept for reference / possible future re-enablement)
// =================================================================================================
// SAP BTP Integration Suite's archiving job (and any correctly-configured test client) only ever
// talks to this mock via the CMIS Browser binding (see the class-level doc comment at the top of this
// file and processData()/parseRequest(), which no longer detect or dispatch AtomPub at all). A user
// mistake in this project (pointing CMIS Workbench at AtomPub instead of Browser binding) caused
// confusing results that were hard to tell apart from a genuine server bug, so the AtomPub binding
// implementation was intentionally disabled by moving every function that only AtomPub needed down
// here, unreferenced by any live code path. Nothing below this banner is called any more.
//
// To re-enable AtomPub support in the future: reintroduce a "BindingType: AtomPub" (or similar) check
// in parseRequest() that sets req.binding = 'ATOM', then branch processData() back to the dispatch
// logic that used to live there (service document / type / children / object GET handling, POST
// createDocument/createFolder dispatch on cmis:objectTypeId, DELETE), using the functions below.
// =================================================================================================

private String objectUrl(String baseUrl, String repositoryId, String objectId) {
    return "${baseUrl}?repositoryId=${urlEncode(repositoryId)}&objectId=${urlEncode(objectId)}&resource=object"
}

private String resourceUrl(String baseUrl, String repositoryId, String resource, String objectId = null) {
    String url = "${baseUrl}?repositoryId=${urlEncode(repositoryId)}&resource=${resource}"
    return objectId == null ? url : "${url}&objectId=${urlEncode(objectId)}"
}

private String ns() {
    return ' xmlns:atom="http://www.w3.org/2005/Atom"' +
        ' xmlns:app="http://www.w3.org/2007/app"' +
        ' xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/"' +
        ' xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/"'
}

/** Service document: what a CMIS client reads first when it initialises a session. */
private String buildServiceDocument(String repositoryId, String baseUrl) {
    String rootId = rootFolderId()
    String children = resourceUrl(baseUrl, repositoryId, 'children', rootId)
    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << "<app:service${ns()}>"
    xml << '<app:workspace>'
    xml << "<atom:title>${escapeXml(repositoryName(repositoryId))}</atom:title>"
    xml << '<cmisra:repositoryInfo>'
    xml << repositoryInfoInner(repositoryId)
    xml << '</cmisra:repositoryInfo>'
    xml << collection(children, 'root', 'Root Collection', 'application/atom+xml;type=entry')
    xml << collection(resourceUrl(baseUrl, repositoryId, 'types'), 'types', 'Types Collection', null)
    xml << collection(resourceUrl(baseUrl, repositoryId, 'unfiled'), 'unfiled', 'Unfiled Collection', 'application/atom+xml;type=entry')
    xml << collection(resourceUrl(baseUrl, repositoryId, 'checkedout'), 'checkedout', 'Checked Out Collection', null)
    xml << "<atom:link rel=\"http://docs.oasis-open.org/ns/cmis/link/200908/typedescendants\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'types'))}\" type=\"application/cmistree+xml\"/>"
    xml << "<atom:link rel=\"http://docs.oasis-open.org/ns/cmis/link/200908/foldertree\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'children', rootId))}\" type=\"application/cmistree+xml\"/>"
    xml << "<atom:link rel=\"http://docs.oasis-open.org/ns/cmis/link/200908/rootdescendants\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'descendants', rootId))}\" type=\"application/cmistree+xml\"/>"
    xml << uriTemplate('objectbyid', 'application/atom+xml;type=entry',
            "${baseUrl}?repositoryId=${urlEncode(repositoryId)}&resource=object&objectId={id}")
    xml << uriTemplate('typebyid', 'application/atom+xml;type=entry',
            "${baseUrl}?repositoryId=${urlEncode(repositoryId)}&resource=type&typeId={id}")
    xml << '</app:workspace>'
    xml << '</app:service>'
    return xml.toString()
}

private String collection(String href, String type, String title, String accept) {
    String acceptXml = accept == null ? '' : "<app:accept>${accept}</app:accept>"
    return "<app:collection href=\"${escapeXml(href)}\"><atom:title>${title}</atom:title>${acceptXml}" +
            "<cmisra:collectionType>${type}</cmisra:collectionType></app:collection>"
}

private String uriTemplate(String type, String mediaType, String template) {
    return "<cmisra:uritemplate><cmisra:template>${escapeXml(template)}</cmisra:template>" +
            "<cmisra:type>${type}</cmisra:type><cmisra:mediatype>${mediaType}</cmisra:mediatype></cmisra:uritemplate>"
}

private String buildFeed(String title, List<String> entries, String baseUrl, String repositoryId, int total = -1) {
    String now = isoTimestamp(new Date())
    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << "<atom:feed${ns()}>"
    xml << "<atom:id>urn:uuid:${UUID.randomUUID()}</atom:id>"
    xml << "<atom:title>${escapeXml(title)}</atom:title>"
    xml << "<atom:updated>${now}</atom:updated>"
    xml << "<atom:link rel=\"self\" href=\"${escapeXml(baseUrl)}?repositoryId=${escapeXml(urlEncode(repositoryId))}\"/>"
    entries.each { xml << stripXmlDecl(it) }
    xml << "<cmisra:numItems>${total >= 0 ? total : entries.size()}</cmisra:numItems>"
    xml << '</atom:feed>'
    return xml.toString()
}

/** Atom entry of a remembered (uploaded) document. */
private String buildDocEntry(Map doc, String baseUrl) {
    return buildAtomEntry(
            objectId: doc.objectId,
            repositoryId: doc.repositoryId,
            documentName: doc.documentName,
            mimeType: doc.mimeType,
            contentLength: (doc.contentLength ?: 0) as long,
            principal: doc.principal ?: 'cpi',
            timestamp: doc.timestamp,
            objectUrl: objectUrl(baseUrl, doc.repositoryId, doc.objectId as String))
}

private String stripXmlDecl(String xml) {
    return xml.replaceFirst(/^<\?xml[^>]*\?>/, '')
}

private String buildFolderEntry(String baseUrl, String repositoryId) {
    String rootId = rootFolderId()
    String now = isoTimestamp(new Date())
    String self = objectUrl(baseUrl, repositoryId, rootId)
    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << "<atom:entry${ns()}>"
    xml << "<atom:id>urn:uuid:${rootId}</atom:id>"
    xml << '<atom:title>root</atom:title>'
    xml << "<atom:published>${now}</atom:published><atom:updated>${now}</atom:updated>"
    xml << '<atom:author><atom:name>system</atom:name></atom:author>'
    xml << "<atom:link rel=\"self\" href=\"${escapeXml(self)}\"/>"
    xml << "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'children', rootId))}\"/>"
    xml << "<atom:link rel=\"service\" type=\"application/atomsvc+xml\" href=\"${escapeXml(baseUrl)}?repositoryId=${escapeXml(urlEncode(repositoryId))}\"/>"
    xml << '<cmisra:object>'
    xml << folderProperties(now)
    xml << '</cmisra:object>'
    xml << '</atom:entry>'
    return xml.toString()
}

/** The flow is stateless, so any id the client asks for after an upload is answered with a plausible document. */
private String buildSyntheticDocumentEntry(String objectId, String baseUrl, String repositoryId) {
    return buildAtomEntry(
            objectId: objectId,
            repositoryId: repositoryId,
            documentName: "document-${objectId}",
            mimeType: 'application/pdf',
            contentLength: 0L,
            principal: 'cpi',
            timestamp: isoTimestamp(new Date()),
            objectUrl: objectUrl(baseUrl, repositoryId, objectId))
}

private String buildTypeEntry(String typeId, String baseUrl, String repositoryId) {
    boolean folder = typeId == 'cmis:folder'
    String now = isoTimestamp(new Date())
    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << "<atom:entry${ns()}>"
    xml << "<atom:id>urn:uuid:${escapeXml(typeId)}</atom:id>"
    xml << "<atom:title>${escapeXml(typeId)}</atom:title>"
    xml << "<atom:updated>${now}</atom:updated>"
    xml << "<atom:link rel=\"self\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'type') + '&typeId=' + urlEncode(typeId))}\"/>"
    // links the AtomPub client needs to navigate the type tree (children / descendants of this type);
    // without them OpenCMIS fails with "Unknown repository or type!" e.g. when the Workbench lists creatable types
    xml << "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'types') + '&typeId=' + urlEncode(typeId))}\"/>"
    xml << "<atom:link rel=\"down\" type=\"application/cmistree+xml\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'typedescendants') + '&typeId=' + urlEncode(typeId))}\"/>"
    xml << "<atom:link rel=\"service\" type=\"application/atomsvc+xml\" href=\"${escapeXml(baseUrl)}?repositoryId=${escapeXml(urlEncode(repositoryId))}\"/>"
    xml << "<cmisra:type xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"cmis:${folder ? 'cmisTypeFolderDefinitionType' : 'cmisTypeDocumentDefinitionType'}\">"
    xml << typeInner(typeId)
    xml << '</cmisra:type>'
    xml << '</atom:entry>'
    return xml.toString()
}

private String buildAtomEntry(Map args) {
    // Raw (unescaped) values - the property*/escapeXml helpers escape at insertion point.
    String objectId = args.objectId as String
    String repositoryId = args.repositoryId as String
    String documentName = args.documentName as String
    String mimeType = args.mimeType as String
    String principal = args.principal as String
    String timestamp = args.timestamp as String
    String objectUrl = args.objectUrl as String
    String contentUrl = "${objectUrl}&streamId=content"
    long contentLength = args.contentLength as long

    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << '<atom:entry xmlns:atom="http://www.w3.org/2005/Atom"'
    xml << ' xmlns:app="http://www.w3.org/2007/app"'
    xml << ' xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/"'
    xml << ' xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">'
    xml << "<atom:id>urn:uuid:${escapeXml(objectId)}</atom:id>"
    xml << "<atom:title>${escapeXml(documentName)}</atom:title>"
    xml << "<atom:published>${escapeXml(timestamp)}</atom:published>"
    xml << "<atom:updated>${escapeXml(timestamp)}</atom:updated>"
    xml << "<atom:author><atom:name>${escapeXml(principal)}</atom:name></atom:author>"
    xml << "<atom:content type=\"${escapeXml(mimeType)}\" src=\"${escapeXml(contentUrl)}\"/>"
    xml << "<atom:link rel=\"self\" href=\"${escapeXml(objectUrl)}\"/>"
    xml << "<atom:link rel=\"edit\" href=\"${escapeXml(objectUrl)}\"/>"
    xml << "<atom:link rel=\"enclosure\" type=\"${escapeXml(mimeType)}\" href=\"${escapeXml(contentUrl)}\"/>"
    xml << "<atom:link rel=\"service\" type=\"application/atomsvc+xml\" href=\"${escapeXml(objectUrl)}\"/>"
    xml << "<cmisra:pathSegment>${escapeXml(documentName)}</cmisra:pathSegment>"
    xml << '<cmisra:object>'
    xml << documentProperties(objectId, repositoryId, documentName, mimeType, contentLength, principal, timestamp)
    xml << '<cmis:allowableActions>'
    xml << '<cmis:canDeleteObject>true</cmis:canDeleteObject>'
    xml << '<cmis:canUpdateProperties>true</cmis:canUpdateProperties>'
    xml << '<cmis:canGetProperties>true</cmis:canGetProperties>'
    xml << '<cmis:canGetContentStream>true</cmis:canGetContentStream>'
    xml << '<cmis:canSetContentStream>true</cmis:canSetContentStream>'
    xml << '<cmis:canDeleteContentStream>true</cmis:canDeleteContentStream>'
    xml << '<cmis:canGetObjectParents>true</cmis:canGetObjectParents>'
    xml << '<cmis:canMoveObject>true</cmis:canMoveObject>'
    xml << '</cmis:allowableActions>'
    xml << '</cmisra:object>'
    xml << '</atom:entry>'

    return xml.toString()
}

private String propertyId(String id, String value) {
    return "<cmis:propertyId propertyDefinitionId=\"${id}\"><cmis:value>${escapeXml(value)}</cmis:value></cmis:propertyId>"
}

private String propertyString(String id, String value) {
    return "<cmis:propertyString propertyDefinitionId=\"${id}\"><cmis:value>${escapeXml(value)}</cmis:value></cmis:propertyString>"
}

private String propertyBoolean(String id, boolean value) {
    return "<cmis:propertyBoolean propertyDefinitionId=\"${id}\"><cmis:value>${value}</cmis:value></cmis:propertyBoolean>"
}

private String propertyInteger(String id, long value) {
    return "<cmis:propertyInteger propertyDefinitionId=\"${id}\"><cmis:value>${value}</cmis:value></cmis:propertyInteger>"
}

private String propertyDateTime(String id, String isoValue) {
    return "<cmis:propertyDateTime propertyDefinitionId=\"${id}\"><cmis:value>${isoValue}</cmis:value></cmis:propertyDateTime>"
}

/**
 * CMIS clients validate the returned properties against the advertised type's property definitions and
 * flag any declared property that is entirely missing from the object (e.g. the CMIS TCK's Object
 * Compliance Check fails with "Property is not included in response!" for cmis:description,
 * cmis:parentId, etc. on the root folder). A property with no value is represented by a self-closing
 * element (no <cmis:value> child) rather than omitting the property altogether.
 */
private String propertyIdNullable(String id, String value) {
    return value == null ? "<cmis:propertyId propertyDefinitionId=\"${id}\"/>" : propertyId(id, value)
}

private String propertyStringNullable(String id, String value) {
    return value == null ? "<cmis:propertyString propertyDefinitionId=\"${id}\"/>" : propertyString(id, value)
}

/** Multi-valued cmis:propertyId (e.g. cmis:secondaryObjectTypeIds, cmis:allowedChildObjectTypeIds). */
private String propertyIdMulti(String id, List values) {
    if (values == null || values.isEmpty()) {
        return "<cmis:propertyId propertyDefinitionId=\"${id}\"/>"
    }
    StringBuilder xml = new StringBuilder("<cmis:propertyId propertyDefinitionId=\"${id}\">")
    values.each { v -> xml << "<cmis:value>${escapeXml(v as String)}</cmis:value>" }
    xml << '</cmis:propertyId>'
    return xml.toString()
}

private String repositoryInfoInner(String repositoryId) {
    String rootId = rootFolderId()
    StringBuilder xml = new StringBuilder()
    xml << "<cmis:repositoryId>${escapeXml(repositoryId)}</cmis:repositoryId>"
    xml << "<cmis:repositoryName>${escapeXml(repositoryName(repositoryId))}</cmis:repositoryName>"
    xml << '<cmis:repositoryDescription>Archive repository exposed via SAP Integration Suite</cmis:repositoryDescription>'
    xml << '<cmis:vendorName>SRB Consulting</cmis:vendorName>'
    xml << '<cmis:productName>SRB Archive Gateway</cmis:productName>'
    xml << '<cmis:productVersion>1.0</cmis:productVersion>'
    xml << "<cmis:rootFolderId>${rootId}</cmis:rootFolderId>"
    xml << '<cmis:capabilities>'
    xml << '<cmis:capabilityACL>none</cmis:capabilityACL>'
    xml << '<cmis:capabilityAllVersionsSearchable>false</cmis:capabilityAllVersionsSearchable>'
    xml << '<cmis:capabilityChanges>none</cmis:capabilityChanges>'
    xml << '<cmis:capabilityContentStreamUpdatability>anytime</cmis:capabilityContentStreamUpdatability>'
    xml << '<cmis:capabilityGetDescendants>false</cmis:capabilityGetDescendants>'
    xml << '<cmis:capabilityGetFolderTree>false</cmis:capabilityGetFolderTree>'
    xml << '<cmis:capabilityMultifiling>false</cmis:capabilityMultifiling>'
    xml << '<cmis:capabilityPWCSearchable>false</cmis:capabilityPWCSearchable>'
    xml << '<cmis:capabilityPWCUpdatable>false</cmis:capabilityPWCUpdatable>'
    xml << '<cmis:capabilityQuery>none</cmis:capabilityQuery>'
    xml << '<cmis:capabilityRenditions>none</cmis:capabilityRenditions>'
    xml << '<cmis:capabilityUnfiling>false</cmis:capabilityUnfiling>'
    xml << '<cmis:capabilityVersionSpecificFiling>false</cmis:capabilityVersionSpecificFiling>'
    xml << '<cmis:capabilityJoin>none</cmis:capabilityJoin>'
    xml << '</cmis:capabilities>'
    xml << '<cmis:cmisVersionSupported>1.1</cmis:cmisVersionSupported>'
    xml << '<cmis:changesIncomplete>true</cmis:changesIncomplete>'
    return xml.toString()
}

private String typeInner(String typeId) {
    boolean folder = isFolderType(typeId)
    String parentId = parentIdFor(typeId)
    StringBuilder xml = new StringBuilder()
    xml << "<cmis:id>${escapeXml(typeId)}</cmis:id>"
    xml << "<cmis:localName>${escapeXml(typeId)}</cmis:localName>"
    xml << '<cmis:localNamespace>http://docs.oasis-open.org/ns/cmis/core/200908/</cmis:localNamespace>'
    xml << "<cmis:displayName>${escapeXml(displayNameFor(typeId))}</cmis:displayName>"
    xml << "<cmis:queryName>${escapeXml(typeId)}</cmis:queryName>"
    xml << "<cmis:baseId>${escapeXml(baseIdFor(typeId))}</cmis:baseId>"
    if (parentId != null) {
        xml << "<cmis:parentId>${escapeXml(parentId)}</cmis:parentId>"
    }
    xml << '<cmis:creatable>true</cmis:creatable>'
    xml << '<cmis:fileable>true</cmis:fileable>'
    xml << '<cmis:queryable>false</cmis:queryable>'
    xml << '<cmis:fulltextIndexed>false</cmis:fulltextIndexed>'
    xml << '<cmis:includedInSupertypeQuery>true</cmis:includedInSupertypeQuery>'
    xml << '<cmis:controllablePolicy>false</cmis:controllablePolicy>'
    xml << '<cmis:controllableACL>false</cmis:controllableACL>'
    // property definitions must precede the type specific elements (versionable, contentStreamAllowed)
    propertyDefinitionList(typeId).each { List d -> xml << propertyDefinitionXml(typeId, d) }
    if (!folder) {
        xml << '<cmis:versionable>false</cmis:versionable>'
        xml << '<cmis:contentStreamAllowed>required</cmis:contentStreamAllowed>'
    }
    return xml.toString()
}

private String propertyDefinitionXml(String typeId, List d) {
    String id = d[0] as String
    String type = d[1] as String
    Map tags = [string: 'propertyStringDefinition', id: 'propertyIdDefinition', datetime: 'propertyDateTimeDefinition',
                boolean: 'propertyBooleanDefinition', integer: 'propertyIntegerDefinition']
    String tag = tags.get(type)
    StringBuilder xml = new StringBuilder()
    xml << "<cmis:${tag}>"
    xml << "<cmis:id>${escapeXml(id)}</cmis:id>"
    xml << "<cmis:localName>${escapeXml(id)}</cmis:localName>"
    xml << '<cmis:localNamespace>http://docs.oasis-open.org/ns/cmis/core/200908/</cmis:localNamespace>'
    xml << "<cmis:displayName>${escapeXml(id)}</cmis:displayName>"
    xml << "<cmis:queryName>${escapeXml(id)}</cmis:queryName>"
    xml << "<cmis:description>${escapeXml(id)}</cmis:description>"
    xml << "<cmis:propertyType>${type}</cmis:propertyType>"
    xml << "<cmis:cardinality>${d[2]}</cmis:cardinality>"
    xml << "<cmis:updatability>${d[3]}</cmis:updatability>"
    xml << '<cmis:inherited>false</cmis:inherited>'
    xml << "<cmis:required>${d[4]}</cmis:required>"
    xml << '<cmis:queryable>false</cmis:queryable>'
    xml << '<cmis:orderable>false</cmis:orderable>'
    xml << "</cmis:${tag}>"
    return xml.toString()
}

private String documentProperties(String objectId, String repositoryId, String documentName, String mimeType,
                                  long contentLength, String principal, String timestamp) {
    StringBuilder xml = new StringBuilder()
    xml << '<cmis:properties>'
    xml << propertyId('cmis:objectId', objectId)
    xml << propertyId('cmis:baseTypeId', 'cmis:document')
    xml << propertyId('cmis:objectTypeId', 'cmis:document')
    xml << propertyString('cmis:name', documentName)
    xml << propertyIdMulti('cmis:secondaryObjectTypeIds', [])
    xml << propertyString('cmis:createdBy', principal)
    xml << propertyString('cmis:lastModifiedBy', principal)
    xml << propertyDateTime('cmis:creationDate', timestamp)
    xml << propertyDateTime('cmis:lastModificationDate', timestamp)
    xml << propertyString('cmis:changeToken', timestamp)
    xml << propertyId('cmis:versionSeriesId', objectId)
    xml << propertyString('cmis:versionLabel', '1.0')
    xml << propertyBoolean('cmis:isLatestVersion', true)
    xml << propertyBoolean('cmis:isMajorVersion', true)
    xml << propertyBoolean('cmis:isLatestMajorVersion', true)
    xml << propertyBoolean('cmis:isVersionSeriesCheckedOut', false)
    xml << propertyStringNullable('cmis:versionSeriesCheckedOutBy', null)
    xml << propertyIdNullable('cmis:versionSeriesCheckedOutId', null)
    xml << propertyStringNullable('cmis:checkinComment', null)
    xml << propertyBoolean('cmis:isImmutable', false)
    xml << propertyInteger('cmis:contentStreamLength', contentLength)
    xml << propertyString('cmis:contentStreamMimeType', mimeType)
    xml << propertyString('cmis:contentStreamFileName', documentName)
    xml << propertyId('cmis:contentStreamId', "${objectId}-content")
    xml << propertyString('cmis:description', "Archived via SAP Integration Suite (repository ${repositoryId})")
    xml << '</cmis:properties>'
    return xml.toString()
}

/**
 * Folder properties XML. Defaults describe the single real folder (root); createFolder() below reuses
 * this with a freshly generated id/name/parentId to fake a plausible (but unpersisted) new folder entry.
 */
private String folderProperties(String now, String objectId = rootFolderId(), String name = '', String path = '/', String parentId = null) {
    StringBuilder xml = new StringBuilder()
    xml << '<cmis:properties>'
    xml << propertyId('cmis:objectId', objectId)
    xml << propertyId('cmis:baseTypeId', 'cmis:folder')
    xml << propertyId('cmis:objectTypeId', 'cmis:folder')
    xml << propertyString('cmis:name', name)
    xml << propertyStringNullable('cmis:description', null)
    xml << propertyIdMulti('cmis:secondaryObjectTypeIds', [])
    xml << propertyString('cmis:path', path)
    xml << propertyIdNullable('cmis:parentId', parentId)
    xml << propertyIdMulti('cmis:allowedChildObjectTypeIds', ['cmis:document', 'cmis:folder'])
    xml << propertyString('cmis:createdBy', 'system')
    xml << propertyString('cmis:lastModifiedBy', 'system')
    xml << propertyDateTime('cmis:creationDate', now)
    xml << propertyDateTime('cmis:lastModificationDate', now)
    xml << '</cmis:properties>'

    return xml.toString()
}

private Message createDocument(Message message, Map headers, String inboundBody, String repositoryId, String baseUrl) {
    Map doc = registerDocument(message, headers, inboundBody, repositoryId, [:])
    String url = objectUrl(baseUrl, repositoryId, doc.objectId)

    String atomEntry = buildAtomEntry(
            objectId: doc.objectId,
            repositoryId: repositoryId,
            documentName: doc.documentName,
            mimeType: doc.mimeType,
            contentLength: doc.contentLength,
            principal: doc.principal,
            timestamp: doc.timestamp,
            objectUrl: url)

    message.setHeader('Location', url)
    message.setHeader('CMISObjectId', doc.objectId)
    return respond(message, 201, 'application/atom+xml;type=entry;charset=UTF-8', atomEntry, repositoryId)
}

/**
 * Fakes a successful folder creation (AtomPub createFolder, detected via cmis:objectTypeId ==
 * 'cmis:folder' on the posted properties). Mirrors createDocument()'s shape but returns a folder
 * entry. Nothing is persisted anywhere - the repository only ever has the one real folder (root).
 */
private Message createFolder(Message message, String inboundBody, String repositoryId, String baseUrl) {
    String name = firstNonBlank(extractCmisProperty(inboundBody, 'cmis:name'),
            extractFirstGroup(inboundBody, /<(?:\w+:)?title[^>]*>([^<]*)<\/(?:\w+:)?title>/), 'folder')
    String newFolderId = UUID.randomUUID().toString()
    String now = isoTimestamp(new Date())
    String url = objectUrl(baseUrl, repositoryId, newFolderId)

    StringBuilder xml = new StringBuilder()
    xml << '<?xml version="1.0" encoding="UTF-8"?>'
    xml << "<atom:entry${ns()}>"
    xml << "<atom:id>urn:uuid:${escapeXml(newFolderId)}</atom:id>"
    xml << "<atom:title>${escapeXml(name)}</atom:title>"
    xml << "<atom:published>${now}</atom:published><atom:updated>${now}</atom:updated>"
    xml << '<atom:author><atom:name>system</atom:name></atom:author>'
    xml << "<atom:link rel=\"self\" href=\"${escapeXml(url)}\"/>"
    xml << "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\" href=\"${escapeXml(resourceUrl(baseUrl, repositoryId, 'children', newFolderId))}\"/>"
    xml << "<atom:link rel=\"service\" type=\"application/atomsvc+xml\" href=\"${escapeXml(baseUrl)}?repositoryId=${escapeXml(urlEncode(repositoryId))}\"/>"
    xml << '<cmisra:object>'
    xml << folderProperties(now, newFolderId, name, "/${name}", rootFolderId())
    xml << '</cmisra:object>'
    xml << '</atom:entry>'

    message.setHeader('Location', url)
    return respond(message, 201, 'application/atom+xml;type=entry;charset=UTF-8', xml.toString(), repositoryId)
}

