package com.nextflow.nftouch.agent.langchain.http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * DeepSeek Context Cache 拦截器。
 * 在发给 DeepSeek API 的请求中自动为 system message 添加 cache_control，
 * 并从响应中累计 prompt_tokens 和 prompt_cache_hit_tokens 用于统计展示。
 * 参考: https://api-docs.deepseek.com/guides/context_caching
 */
public class DeepSeekCacheInterceptor implements Interceptor {

    public static long totalPromptTokens = 0;
    public static long totalCacheHitTokens = 0;

    public static void reset() {
        totalPromptTokens = 0;
        totalCacheHitTokens = 0;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String url = request.url().toString();
        boolean isDeepSeek = url.contains("deepseek");

        // === 请求：为 system message 添加 cache_control ===
        if (isDeepSeek) {
            RequestBody body = request.body();
            if (body != null) {
                Buffer buffer = new Buffer();
                body.writeTo(buffer);
                try {
                    JSONObject json = new JSONObject(buffer.readUtf8());
                    JSONArray messages = json.optJSONArray("messages");
                    if (messages != null && messages.length() > 0) {
                        JSONObject firstMsg = messages.getJSONObject(0);
                        if ("system".equals(firstMsg.optString("role"))) {
                            JSONObject cacheControl = new JSONObject();
                            cacheControl.put("type", "ephemeral");
                            firstMsg.put("cache_control", cacheControl);
                        }
                    }
                    String modified = json.toString();
                    MediaType contentType = body.contentType();
                    RequestBody newBody = RequestBody.create(contentType, modified);
                    request = request.newBuilder().method(request.method(), newBody).build();
                } catch (Exception ignored) {
                    // JSON 解析失败则原样发送
                }
            }
        }

        Response response = chain.proceed(request);

        // === 响应：累计 cache 统计 ===
        if (isDeepSeek) {
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                String bodyStr = responseBody.string();
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    JSONObject usage = json.optJSONObject("usage");
                    if (usage != null) {
                        totalPromptTokens += usage.optLong("prompt_tokens", 0);
                        totalCacheHitTokens += usage.optLong("prompt_cache_hit_tokens", 0);
                    }
                } catch (Exception ignored) {
                    // usage 字段缺失则跳过
                }
                response = response.newBuilder()
                        .body(ResponseBody.create(responseBody.contentType(), bodyStr))
                        .build();
            }
        }

        return response;
    }
}
