package script.v2

import com.sap.it.script.v2.api.Message

def Message processData(Message message) {

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        // Exception is available in the Camel exchange property
        def ex = message.getProperty("CamelExceptionCaught")
        def errorText = ex != null ? ex.toString() : "Unknown error"
        // Store exception text and error flag as MPL custom header properties
        messageLog.addCustomHeaderProperty("ErrorText", errorText)
        messageLog.addCustomHeaderProperty("ErrorOccured", "true")
    }

    return message
}
