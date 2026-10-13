package com.esibuy.esibuy_backend.configuracion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Como se guardan los documentos en MongoDB.
 */
@Configuration
public class ConfiguracionMongo {

    /**
     * Por defecto Spring Data convierte un LocalDate a la medianoche de la zona horaria del servidor: en Madrid,
     * el 15/05/2000 se guardaba como 2000-05-14T22:00:00Z, y un servidor en UTC lo leeria como el dia 14 (y calcularia
     * mal la mayoria de edad). Con los codecs del driver, un LocalDate se guarda a medianoche UTC
     * (2000-05-15T00:00:00Z) y se lee igual sea cual sea la zona del servidor.
     */
    @Bean
    public MongoCustomConversions conversionesMongo() {
        return MongoCustomConversions.create(conversiones -> conversiones.useNativeDriverJavaTimeCodecs());
    }

    /**
     * Igual que el conversor que crea Spring Boot, pero sin el campo "_class": Spring Data lo anade a cada
     * documento para poder leer jerarquias de clases, y aqui cada coleccion guarda un unico tipo. Asi los
     * documentos contienen solo los campos del esquema de la BBDD.
     *
     * Si algun dia se guardan subclases distintas en una misma coleccion, habria que volver a activarlo.
     */
    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory fabrica, MongoMappingContext contexto,
                                                       MongoCustomConversions conversiones) {
        MappingMongoConverter conversor = new MappingMongoConverter(new DefaultDbRefResolver(fabrica), contexto);
        conversor.setCustomConversions(conversiones);
        conversor.setTypeMapper(new DefaultMongoTypeMapper(null));
        return conversor;
    }
}
