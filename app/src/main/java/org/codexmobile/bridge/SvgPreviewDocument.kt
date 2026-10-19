package org.codexmobile.bridge

import java.util.Base64

internal const val SVG_PREVIEW_MAX_BYTES = 10L * 1024L * 1024L

internal fun buildSvgPreviewHtml(svgBytes: ByteArray): String {
    require(svgBytes.isNotEmpty()) { "SVG 文件为空" }
    require(svgBytes.size <= SVG_PREVIEW_MAX_BYTES) { "SVG 文件超过预览上限" }
    val encoded = Base64.getEncoder().encodeToString(svgBytes)
    return """<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=0.25,maximum-scale=8,user-scalable=yes">
  <style>
    html,body{margin:0;width:100%;min-height:100%;background:#f7f7f7;}
    body{display:flex;align-items:center;justify-content:center;}
    img{display:block;width:100%;height:auto;}
  </style>
</head>
<body><img alt="SVG 预览" src="data:image/svg+xml;base64,$encoded"></body>
</html>"""
}
