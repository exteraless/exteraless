package tw.nekomimi.nekogram.llm.preset

import org.telegram.messenger.R

object PresetRegistry {

    const val CUSTOM = 0
    const val OPENAI = 1
    const val GOOGLE_AI_STUDIO = 2
    const val GROQ = 3
    const val DEEPSEEK = 4
    const val XAI = 5
    const val CEREBRAS = 6
    const val OLLAMA_CLOUD = 7
    const val OPENROUTER = 8
    const val VERCEL_AI_GATEWAY = 9
    const val GOOGLE_AGENT_PLATFORM = 10
    const val REQUESTY = 11

    private val presetBaseUrls = mapOf(
        OPENAI to "https://api.openai.com/v1",
        GOOGLE_AI_STUDIO to "https://generativelanguage.googleapis.com/v1beta/openai",
        GROQ to "https://api.groq.com/openai/v1",
        DEEPSEEK to "https://api.deepseek.com/v1",
        XAI to "https://api.x.ai/v1",
        CEREBRAS to "https://api.cerebras.ai/v1",
        OLLAMA_CLOUD to "https://ollama.com/v1",
        OPENROUTER to "https://openrouter.ai/api/v1",
        REQUESTY to "https://router.requesty.ai/v1",
        VERCEL_AI_GATEWAY to "https://ai-gateway.vercel.sh/v1",
        GOOGLE_AGENT_PLATFORM to "https://aiplatform.googleapis.com/v1",
    )

    private val defaultModelResIds = mapOf(
        CUSTOM to R.string.LlmModelNameDefault,
        OPENAI to R.string.LlmProviderOpenAIModel,
        GOOGLE_AI_STUDIO to R.string.LlmProviderGeminiModel,
        GROQ to R.string.LlmProviderGroqModel,
        DEEPSEEK to R.string.LlmProviderDeepSeekModel,
        XAI to R.string.LlmProviderXAIModel,
        CEREBRAS to R.string.LlmProviderCerebrasModel,
        OLLAMA_CLOUD to R.string.LlmProviderOllamaCloudModel,
        OPENROUTER to R.string.LlmProviderOpenRouterModel,
        REQUESTY to R.string.LlmProviderRequestyModel,
        VERCEL_AI_GATEWAY to R.string.LlmProviderVercelAIGatewayModel,
    )

    @JvmStatic
    fun getPresetBaseUrl(preset: Int): String? {
        return presetBaseUrls[preset]
    }

    @JvmStatic
    fun getDefaultModelResId(preset: Int): Int {
        return defaultModelResIds[preset] ?: R.string.LlmModelNameDefault
    }
}
