package com.example.myapplication.ui.print

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

object PrintService {
    fun printReceipt(context: Context, receiptText: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "订单打印 - ${System.currentTimeMillis()}"
        
        // 将文本转换为HTML格式以便打印
        val htmlContent = convertToHtmlSimpleBorders(receiptText)
        
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    printAdapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("standard", "标准", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                        .build()
                )
            }
        }
        
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }

    private fun convertToHtmlSimpleBorders(text: String): String {
        val html = StringBuilder()
        html.append("<html><head><meta charset='UTF-8'>")
        html.append("<style>")
        html.append("@page { margin-top: 5mm; }")
        html.append("body { font-family: 'SimSun','NSimsun','Courier New','Monaco','DejaVu Sans Mono', monospac; font-size: 11pt; padding: 0px; }")
        html.append(".bordered-line { border-bottom: 1px solid; padding: 4px 0; white-space: pre; }")
        html.append("</style></head><body>")

        // 按行分割文本
        val lines = text.split("\n")
        lines.forEach { line ->
            val escapedLine = line.replace("<", "&lt;").replace(">", "&gt;")
            html.append("<div class='bordered-line'>$escapedLine</div>")
        }

        html.append("</body></html>")
        return html.toString()
    }
}
