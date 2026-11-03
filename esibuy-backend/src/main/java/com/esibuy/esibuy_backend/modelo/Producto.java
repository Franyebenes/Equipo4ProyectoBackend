package com.esibuy.esibuy_backend.modelo;

import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Producto de un vendedor (coleccion "productos"). Sigue el esquema de la BBDD: idVendedor e idCategorias se guardan
 * como ObjectId, "visible" indica si el producto esta activo. Se crea con {@link #builder()}.
 */
@Document(collection = "productos")
public class Producto {

    @Id
    private String id;
    @Field(name = "idVendedor", targetType = FieldType.OBJECT_ID)
    private String idVendedor;
    @Field(name = "idCategorias", targetType = FieldType.OBJECT_ID)
    private List<String> idCategorias;
    @Field("nombre")
    private String nombre;
    @Field("descripcion")
    private String descripcion;
    @Field("imagen")
    private String imagen;
    @Field("precio")
    private PrecioProducto precio;
    @Field("stock")
    private Integer stock;
    @Field("visible")
    private Boolean visible;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Producto() {
    }

    private Producto(Builder builder) {
        this.id = builder.id;
        this.idVendedor = builder.idVendedor;
        this.idCategorias = builder.idCategorias;
        this.nombre = builder.nombre;
        this.descripcion = builder.descripcion;
        this.imagen = builder.imagen;
        this.precio = builder.precio;
        this.stock = builder.stock;
        this.visible = builder.visible;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getIdVendedor() {
        return idVendedor;
    }

    public List<String> getIdCategorias() {
        return idCategorias;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getImagen() {
        return imagen;
    }

    public PrecioProducto getPrecio() {
        return precio;
    }

    public Integer getStock() {
        return stock;
    }

    public Boolean getVisible() {
        return visible;
    }

    @Override
    public String toString() {
        return "Producto[id=" + id + ", idVendedor=" + idVendedor + ", nombre=" + nombre + "]";
    }

    public static final class Builder {

        private String id;
        private String idVendedor;
        private List<String> idCategorias;
        private String nombre;
        private String descripcion;
        private String imagen;
        private PrecioProducto precio;
        private Integer stock;
        private Boolean visible;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder idVendedor(String idVendedor) {
            this.idVendedor = idVendedor;
            return this;
        }

        public Builder idCategorias(List<String> idCategorias) {
            this.idCategorias = idCategorias;
            return this;
        }

        public Builder nombre(String nombre) {
            this.nombre = nombre;
            return this;
        }

        public Builder descripcion(String descripcion) {
            this.descripcion = descripcion;
            return this;
        }

        public Builder imagen(String imagen) {
            this.imagen = imagen;
            return this;
        }

        public Builder precio(PrecioProducto precio) {
            this.precio = precio;
            return this;
        }

        public Builder stock(Integer stock) {
            this.stock = stock;
            return this;
        }

        public Builder visible(Boolean visible) {
            this.visible = visible;
            return this;
        }

        public Producto build() {
            return new Producto(this);
        }
    }
}
