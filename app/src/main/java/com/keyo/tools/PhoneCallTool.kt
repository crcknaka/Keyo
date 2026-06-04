package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

class PhoneCallTool : Tool {
    override val name = "phone_call"
    override val uiLabel = "📞 Call"
    override val uiExample = "Call Mom"
    override val description = "Make a phone call to a contact by name or phone number. Opens the dialer."
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "contact": {
                    "type": "string",
                    "description": "Contact name or phone number to call"
                }
            },
            "required": ["contact"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val contact = args.getString("contact")

        // Try to find contact by name first
        val number = findContactNumber(context, contact) ?: contact

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Dialing: $number")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't open dialer: ${e.message}")
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
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (_: Exception) { null }
    }
}

