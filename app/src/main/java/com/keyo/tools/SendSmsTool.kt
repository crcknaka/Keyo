package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONObject

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val uiLabel = "💬 SMS"
    override val uiExample = "Text: running 10 min late"
    override val description = "Open SMS app to send a text message to a contact or phone number"
    override val sensitive = true
    override fun confirmSummary(args: JSONObject) =
        "Text ${args.optString("contact")}: “${args.optString("message")}”?"
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "contact": {
                    "type": "string",
                    "description": "Contact name or phone number"
                },
                "message": {
                    "type": "string",
                    "description": "Message text to send"
                }
            },
            "required": ["contact", "message"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val contact = args.getString("contact")
        val message = args.getString("message")

        val number = findContactNumber(context, contact) ?: contact

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(number)}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Opening SMS to $number: $message")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't open SMS: ${e.message}")
        }
    }

    private fun findContactNumber(context: Context, name: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}

