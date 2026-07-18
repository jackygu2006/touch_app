package com.nextflow.nftouch.agent.llm

import com.nextflow.nftouch.agent.AgentConfig
import com.nextflow.nftouch.agent.DefaultAgentService
import com.nextflow.nftouch.agent.LlmProvider
import com.nextflow.nftouch.agent.langchain.http.OkHttpClientBuilderAdapter

object LlmClientFactory {

    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
            if (config.baseUrl.contains("deepseek")) {
                setDeepseekCacheEnabled(true)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
        }
    }
}
