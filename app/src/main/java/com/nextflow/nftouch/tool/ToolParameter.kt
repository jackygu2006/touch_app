package com.nextflow.nftouch.tool

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val isRequired: Boolean
)
