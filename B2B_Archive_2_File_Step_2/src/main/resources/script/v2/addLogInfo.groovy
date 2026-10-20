package script.v2

import com.sap.it.script.v2.api.Message

/*
 * Adds all message properties whose name starts with "b2b:" as custom header
 * properties to the MPL.
 */
def Message processData(Message message) {

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        message.getProperties().each { name, value ->
            if (name != null && name.toString().startsWith("b2b:") && value != null) {
                messageLog.addCustomHeaderProperty(name.toString(), value.toString())
            }
        }
    }

    return message
}
