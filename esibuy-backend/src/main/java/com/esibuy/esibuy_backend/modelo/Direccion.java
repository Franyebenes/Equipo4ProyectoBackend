package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Direccion de envio de un cliente . Coleccion "direcciones"; idUsuario se guarda como ObjectId. */
@Document(collection = "direcciones")
public class Direccion {

    @Id
    private String id;
    @Field(name = "idUsuario", targetType = FieldType.OBJECT_ID)
    private String idUsuario;
    @Field("destinatario")
    private String destinatario;
    @Field("calle")
    private String calle;
    @Field("numero")
    private String numero;
    @Field("pisoPuerta")
    private String pisoPuerta;
    @Field("codigoPostal")
    private String codigoPostal;
    @Field("ciudad")
    private String ciudad;
    @Field("provincia")
    private String provincia;
    @Field("predeterminada")
    private boolean predeterminada;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Direccion() {
    }

    public Direccion(String idUsuario) {
        this.idUsuario = idUsuario;
    }

    /** El servicio ya ha validado y normalizado los valores. */
    public void actualizarDatos(String destinatario, String calle, String numero, String pisoPuerta,
                                String codigoPostal, String ciudad, String provincia) {
        this.destinatario = destinatario;
        this.calle = calle;
        this.numero = numero;
        this.pisoPuerta = pisoPuerta;
        this.codigoPostal = codigoPostal;
        this.ciudad = ciudad;
        this.provincia = provincia;
    }

    public void marcarPredeterminada(boolean predeterminada) {
        this.predeterminada = predeterminada;
    }

    /** "Calle Mayor 5, 2B": la linea de calle que se copia al pedido (DireccionEnvio.calle). */
    public String lineaCalle() {
        String linea = calle + " " + numero;
        return pisoPuerta == null ? linea : linea + ", " + pisoPuerta;
    }

    public String getId() { return id; }
    public String getIdUsuario() { return idUsuario; }
    public String getDestinatario() { return destinatario; }
    public String getCalle() { return calle; }
    public String getNumero() { return numero; }
    public String getPisoPuerta() { return pisoPuerta; }
    public String getCodigoPostal() { return codigoPostal; }
    public String getCiudad() { return ciudad; }
    public String getProvincia() { return provincia; }
    public boolean isPredeterminada() { return predeterminada; }

    /** Sin datos personales: puede acabar en un log. */
    @Override
    public String toString() {
        return "Direccion[id=" + id + ", predeterminada=" + predeterminada + "]";
    }
}