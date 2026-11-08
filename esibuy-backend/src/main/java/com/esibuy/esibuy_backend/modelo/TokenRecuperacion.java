package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/*
 * Token de recuperacion de contrasena (coleccion "tokens_recuperacion"). Solo se guarda el hash SHA-256 del token: el
 * valor en claro existe unicamente en el correo enviado al usuario. idUsuario se guarda como ObjectId, igual que en las
 * demas colecciones. Lo crea y lo modifica solo {@code ServicioTokensRecuperacion}.
 */
@Document(collection = "tokens_recuperacion")
public class TokenRecuperacion {

    @Id
    private String id;
    @Field(name = "idUsuario", targetType = FieldType.OBJECT_ID)
    private String idUsuario;
    @Field("hashToken")
    private String hashToken;
    @Field("estado")
    private EstadoToken estado;
    @Field("creadoEn")
    private Instant creadoEn;
    @Field("caducaEn")
    private Instant caducaEn;
    @Field("usadoEn")
    private Instant usadoEn;

    // Usado por Spring Data al leer de MongoDB. 
    protected TokenRecuperacion() {
    }

    // Token recien emitido: vigente y sin usar.
    public TokenRecuperacion(String idUsuario, String hashToken, Instant creadoEn, Instant caducaEn) {
        this.idUsuario = idUsuario;
        this.hashToken = hashToken;
        this.estado = EstadoToken.VIGENTE;
        this.creadoEn = creadoEn;
        this.caducaEn = caducaEn;
    }

    public String getId() {
        return id;
    }

    public String getIdUsuario() {
        return idUsuario;
    }

    public String getHashToken() {
        return hashToken;
    }

    public EstadoToken getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getCaducaEn() {
        return caducaEn;
    }

    public Instant getUsadoEn() {
        return usadoEn;
    }

    // Nunca incluye el hash: no hace falta en un log y no debe poder usarse para buscar el token.
    @Override
    public String toString() {
        return "TokenRecuperacion[id=" + id + ", idUsuario=" + idUsuario + ", estado=" + estado + "]";
    }
}
