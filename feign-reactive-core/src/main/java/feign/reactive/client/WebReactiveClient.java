package feign.reactive.client;

import feign.MethodMetadata;
import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;
import feign.reactive.Logger;
import org.reactivestreams.Publisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static feign.Util.resolveLastTypeParameter;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * @author Sergii Karpenko
 */
public class WebReactiveClient implements ReactiveClient {

    private final WebClient webClient;
    private final String methodTag;
    private MethodMetadata metadata;
    private final ErrorDecoder errorDecoder;
    private final boolean decode404;
    private final Logger logger;
    private final Type returnPublisherType;
    private final ParameterizedTypeReference<?> returnActualType;

    public WebReactiveClient(MethodMetadata metadata,
                             WebClient webClient,
                             ErrorDecoder errorDecoder,
                             boolean decode404,
                             Logger logger) {
        this.webClient = webClient;
        this.metadata = metadata;
        this.errorDecoder = errorDecoder;
        this.decode404 = decode404;
        this.logger = logger;

        this.methodTag = metadata.configKey().substring(0, metadata.configKey().indexOf('('));
        final Type returnType = metadata.returnType();
        returnPublisherType = ((ParameterizedType) returnType).getRawType();
        returnActualType = ParameterizedTypeReference.forType(
                resolveLastTypeParameter(returnType, (Class<?>) returnPublisherType));
    }


    @Override
    public Publisher executeRequest(Request request) {
        logger.logRequest(methodTag, request);

        long start = System.currentTimeMillis();
        WebClient.ResponseSpec response = webClient.method(HttpMethod.resolve(request.method()))
                .uri(request.url())
                .headers(httpHeaders -> request.headers().forEach(
                        (key, value) -> httpHeaders.put(key, (List<String>) value)))
                .body(request.body() != null ? BodyInserters.fromObject(request.body()) : BodyInserters.empty())
                .retrieve()
                .onStatus(httpStatus -> decode404 && httpStatus == NOT_FOUND,
                        clientResponse -> null)
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(ByteArrayResource.class)
                                .map(ByteArrayResource::getByteArray)
                                .defaultIfEmpty(new byte[0])
                                .map(bodyData -> errorDecoder.decode(metadata.configKey(),
                                        Response.create(
                                                clientResponse.statusCode().value(),
                                                clientResponse.statusCode().getReasonPhrase(),
                                                clientResponse.headers().asHttpHeaders().entrySet().stream()
                                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                                bodyData)))

                )
                .onStatus(httpStatus -> true, clientResponse -> {
                    logger.logResponseHeaders(methodTag, clientResponse.headers().asHttpHeaders());
                    return null;
                });

        if (returnPublisherType == Mono.class) {
            return response.bodyToMono(returnActualType)
                    .map(result -> {
                        logger.logResponse(methodTag, result, System.currentTimeMillis() - start);
                        return result;
                    });
        } else {
            return response.bodyToFlux(returnActualType)
                    .map(result -> {
                        logger.logResponse(methodTag, result, System.currentTimeMillis() - start);
                        return result;
                    });
        }
    }
}
