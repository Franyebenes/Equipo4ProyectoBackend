package com.esibuy.esibuy_backend.modelo;

import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Producto de un vendedor (coleccion "productos"). Sigue el esquema de la BBDD: idVendedor e idCategorias se guardan
 * como ObjectId, "visible" indica si el producto esta activo y "soloPremium" si solo lo ven los clientes premium.
 * Se crea con {@link #builder()}.
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
    // HU15.1 (sprint 2): visible solo para clientes premium. Los productos antiguos no lo tienen (null = para todos)
    @Field("soloPremium")
    private Boolean soloPremium;

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
        this.soloPremium = builder.soloPremium;
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

    public Boolean getSoloPremium() {
        return soloPremium;
    }

    /** Activa (visible para los clientes) o desactiva el producto sin borrarlo. */
    public void cambiarVisibilidad(boolean visible) {
        this.visible = visible;
    }

    /** HU15.1 (sprint 2): true si solo lo pueden ver los clientes premium. */
    public void cambiarSoloPremium(boolean soloPremium) {
        this.soloPremium = soloPremium;
    }

    /** HU15.1 (sprint 2): descuento exclusivo para clientes premium (porcentaje); null si no tiene. */
    public void cambiarDescuentoPremium(Double descuentoPremium) {
        Double base = precio == null ? null : precio.getBase();
        Double descuento = precio == null ? null : precio.getDescuento();
        this.precio = new PrecioProducto(base, descuento, descuentoPremium);
    }

    /** Unidades disponibles. Con 0 el producto no esta disponible para la venta. */
    public void cambiarStock(int stock) {
        this.stock = stock;
    }

    /**
     * Datos que edita el vendedor. Se conserva el descuento premium, que se cambia con
     * {@link #cambiarDescuentoPremium(Double)}. El stock y el estado se cambian con {@link #cambiarStock(int)} y
     * {@link #cambiarVisibilidad(boolean)}.
     */
    public void modificarDatos(String nombre, String descripcion, List<String> idCategorias, String imagen,
                               Double precioBase, Double descuento) {
        Double descuentoPremium = precio == null ? null : precio.getDescuentoPremium();
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.idCategorias = idCategorias;
        this.imagen = imagen;
        this.precio = new PrecioProducto(precioBase, descuento, descuentoPremium);
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
        private Boolean soloPremium;

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

        public Builder soloPremium(Boolean soloPremium) {
            this.soloPremium = soloPremium;
            return this;
        }

        public Producto build() {
            return new Producto(this);
        }
    }
}