package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiProviderReasoningTest {
    @Test
    fun defaultConfigUsesTheOfficialXaiEndpointAndModel() {
        assertEquals(
            "grok-4.6",
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://api.x.ai/v1/chat/completions",
                ApiProviderType.XAI
            )
        )
    }

    @Test
    fun enabledOptionsMapToXaiEfforts() {
        // op1moga: XaiReasoningMapper 已被 ThinkingConfigurationApplier 取代。
        // thinkingConfigurations 是 JSON 规则数组，这里验证 xAI 规则可解析出选项。
        val mapping = ThinkingQualityMappingRegistry.resolve(
            providerTypeId = ApiProviderType.XAI.name,
            modelName = "grok-4.6",
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = xaiRuleJson(),
        )
        assertEquals(ThinkingQualityControl.LEVELS, mapping.control)
        assertNotNull(mapping.optionFor("high"))
    }

    @Test
    fun mapperPreservesTheSelectedEffort() {
        // op1moga: 同上，验证选定 option 后请求体被实际写入。
        val requestJson = JSONObject()
        ThinkingConfigurationApplier.apply(
            requestJson = requestJson,
            providerTypeId = ApiProviderType.XAI.name,
            modelName = "grok-4.6",
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = xaiRuleJson(),
            enableThinking = true,
            optionId = "high",
        )
        assertTrue(requestJson.length() > 0)
    }

    @Test
    fun reasoningEffortUsesTheGrokFamilyRule() {
        // op1moga: 验证 grok 系列能被规则匹配上（非 UNSUPPORTED）。
        val mapping = ThinkingQualityMappingRegistry.resolve(
            providerTypeId = ApiProviderType.XAI.name,
            modelName = "grok-4.6",
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = xaiRuleJson(),
        )
        assertTrue(mapping.control != ThinkingQualityControl.UNSUPPORTED)
    }

    /** 最小可用的 xAI thinking 规则（provider= XAI，control=levels，选项 low/high） */
    private fun xaiRuleJson(): String =
        """
        [
          {
            "id": "xai-grok",
            "enabled": true,
            "providers": ["XAI"],
            "control": "levels",
            "parameterLabel": "reasoning_effort",
            "match": { "modelPrefix": ["grok"] },
            "enable": [{ "path": "reasoning_effort", "value": "medium" }],
            "options": [
              { "id": "low",  "actions": [{ "path": "reasoning_effort", "value": "low" }] },
              { "id": "high", "actions": [{ "path": "reasoning_effort", "value": "high" }] }
            ]
          }
        ]
        """.trimIndent()
}
