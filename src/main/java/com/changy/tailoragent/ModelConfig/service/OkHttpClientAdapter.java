package com.changy.tailoragent.ModelConfig.service;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * 基于 OkHttp 的 OpenAI SDK {@link HttpClient} 适配器。
 * <p>
 * OpenAI Java SDK 4.39.1 的 {@code ClientOptions.Builder.build()}
 * 要求必须设置 {@code httpClient}，而 {@code openai-java-okhttp}
 * 模块不在 Maven 仓库中。因此直接封装 OkHttp 来实现该接口。
 * <p>
 * OkHttp 4.12.0 已由 {@code spring-ai-openai} 传递引入，无需额外依赖。
 */
public class OkHttpClientAdapter implements HttpClient {

    private final OkHttpClient okHttpClient;

    public OkHttpClientAdapter(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        Request okRequest = toOkHttpRequest(request);
        try {
            Response okResponse = okHttpClient.newCall(okRequest).execute();
            return toOpenAiResponse(okResponse);
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + request.url(), e);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        Request okRequest = toOkHttpRequest(request);
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        okHttpClient.newCall(okRequest).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NotNull okhttp3.Call call, @NotNull IOException e) {
                future.completeExceptionally(
                        new RuntimeException("HTTP request failed: " + request.url(), e));
            }

            @Override
            public void onResponse(@NotNull okhttp3.Call call, @NotNull Response response) {
                future.complete(toOpenAiResponse(response));
            }
        });
        return future;
    }

    @Override
    public void close() {
        // OkHttpClient 内部管理连接池，无需手动关闭单个实例
    }

    // ---- 请求转换 ----

    private Request toOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.url());

        // 请求头
        for (String name : request.headers().names()) {
            for (String value : request.headers().values(name)) {
                builder.addHeader(name, value);
            }
        }

        // 请求体
        RequestBody body = toOkHttpBody(request.body());
        if (body == null) {
            builder.method(request.method().name(), null);
        }

        return builder.method(request.method().name(), body).build();
    }

    private RequestBody toOkHttpBody(HttpRequestBody body) {
        if (body == null) {
            return null;
        }
        MediaType mediaType = body.contentType() != null
                ? MediaType.parse(body.contentType())
                : null;
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return mediaType;
            }

            @Override
            public long contentLength() throws IOException {
                return body.contentLength();
            }

            @Override
            public void writeTo(@NotNull BufferedSink sink) throws IOException {
                try (OutputStream os = sink.outputStream()) {
                    body.writeTo(os);
                }
            }
        };
    }

    // ---- 响应转换 ----

    private HttpResponse toOpenAiResponse(Response okResponse) {
        return new HttpResponse() {
            @Override
            public int statusCode() {
                return okResponse.code();
            }

            @Override
            public Headers headers() {
                Headers.Builder builder = Headers.builder();
                for (String name : okResponse.headers().names()) {
                    builder.put(name, okResponse.headers().values(name));
                }
                return builder.build();
            }

            @Override
            public InputStream body() {
                if (okResponse.body() != null) {
                    return okResponse.body().byteStream();
                }
                return InputStream.nullInputStream();
            }

            @Override
            public void close() {
                okResponse.close();
            }
        };
    }
}
