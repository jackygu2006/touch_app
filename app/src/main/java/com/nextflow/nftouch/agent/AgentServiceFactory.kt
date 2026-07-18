package com.nextflow.nftouch.agent

object AgentServiceFactory {

    @JvmStatic
    fun create(): AgentService = DefaultAgentService()
}
