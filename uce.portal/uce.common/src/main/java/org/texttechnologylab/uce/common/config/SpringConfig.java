package org.texttechnologylab.uce.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@Import({DocumentAccessConfig.class})
public class SpringConfig {

    @Bean
    public DataInterface databaseService() {
        if (UceStorageBackend.configured() == UceStorageBackend.DUA) {
            return (DataInterface) Proxy.newProxyInstance(
                    DataInterface.class.getClassLoader(),
                    new Class[]{DataInterface.class},
                    new UnsupportedBackendInvocationHandler()
            );
        }
        throw new IllegalStateException("Unsupported UCE storage backend configuration. Only DUA-mode is supported.");
    }

    private static final class UnsupportedBackendInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "UnsupportedBackendDataInterface";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (method.isDefault()) {
                return invokeDefaultMethod(proxy, method, args);
            }
            if ("backendName".equals(method.getName())) {
                return "dua";
            }

            return fallback(method.getReturnType());
        }

        private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) {
            try {
                MethodHandle handle = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .findSpecial(method.getDeclaringClass(), method.getName(),
                                MethodType.methodType(method.getReturnType(), method.getParameterTypes()), method.getDeclaringClass())
                        .bindTo(proxy);
                return handle.invokeWithArguments(args == null ? new Object[0] : args);
            } catch (Throwable ex) {
                throw new IllegalStateException("Failed to invoke default DataInterface method " + method.getName(), ex);
            }
        }

        private Object fallback(Class<?> returnType) {
            if (returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == char.class) {
                return '\0';
            }
            if (returnType == String.class) {
                return "dua";
            }
            if (returnType == Boolean.class) {
                return false;
            }
            if (returnType == Integer.class) {
                return 0;
            }
            if (returnType == Long.class) {
                return 0L;
            }
            if (returnType == Double.class) {
                return 0d;
            }
            if (returnType == Float.class) {
                return 0f;
            }
            if (returnType == Short.class) {
                return (short) 0;
            }
            if (returnType == Byte.class) {
                return (byte) 0;
            }
            if (returnType == Character.class) {
                return '\0';
            }
            if (returnType == List.class) {
                return List.of();
            }
            if (returnType == Map.class) {
                return Map.of();
            }
            if (returnType == Set.class) {
                return Set.of();
            }
            if (returnType == java.util.Set.class) {
                return Set.of();
            }
            if (returnType == ArrayList.class) {
                return new ArrayList<>();
            }
            if (returnType == LinkedHashMap.class) {
                return new LinkedHashMap<>();
            }
            if (returnType == java.util.concurrent.Future.class) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return null;
        }
    }

    @Bean
    public StorageMaintenanceService storageMaintenanceService(DataInterface databaseService) {
        if (databaseService instanceof PostgresqlDataInterface_Impl postgres) {
            return new PostgresStorageMaintenanceService(postgres);
        }
        return new DuaStorageMaintenanceService(databaseService);
    }

    @Bean
    public UCEBackend uceBackend(DataInterface databaseService, StorageMaintenanceService storageMaintenanceService) {
        return new UCEBackend(databaseService, storageMaintenanceService);
    }

    @Bean
    public LexiconService lexiconService(DataInterface databaseService) {
        return new LexiconService(databaseService);
    }

    @Bean
    public AuthenticationService authenticationService() {return new AuthenticationService();}

    @Bean
    public MapService mapService(DataInterface databaseService) {
        return new MapService(databaseService);
    }

    @Bean
    public WikiService wikiService(DataInterface databaseService, RAGService ragService, JenaSparqlService jenaSparqlService) {
        return new WikiService(databaseService, ragService, jenaSparqlService);
    }

    @Bean
    public GoetheUniversityService goetheUniversityService() {
        return new GoetheUniversityService();
    }

    @Bean
    public GbifService gbifService() {
        return new GbifService(jenaSparqlService());
    }

    @Bean
    public JenaSparqlService jenaSparqlService() {
        return new JenaSparqlService();
    }

    @Bean
    public RAGService ragService(DataInterface databaseService, DocumentAccessManager accessManager) {
        return new RAGService(databaseService, accessManager);
    }

    @Bean
    public EmbeddingService embeddingService(DataInterface databaseService, DocumentAccessManager accessManager) {
        return new EmbeddingService(databaseService, accessManager);
    }

    @Bean
    public S3StorageService s3Storage() {
        return new S3StorageService();
    }

}
