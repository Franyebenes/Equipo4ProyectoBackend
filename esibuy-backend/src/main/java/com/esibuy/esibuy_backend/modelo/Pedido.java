package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Pedido de un cliente (coleccion "pedidos"). Se crea a partir del carrito y nace PAGADO (el pago es simulado).
 * El stock ya quedo reservado al meter los productos en el carrito, asi que solo se devuelve si se cancela.
 */
@Document(collection = "pedidos")
public class Pedido {

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field("estado")
    private EstadoPedido estado;
    @Field("precioTotal")
    private double precioTotal;
    @Field("fecha")
    private Instant fecha;
    @Field("direccionEnvio")
    private DireccionEnvio direccionEnvio;
    @Field("lineas")
    private List<LineaPedido> lineas;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Pedido() {
    }

    public Pedido(String idCliente, DireccionEnvio direccionEnvio, List<LineaPedido> lineas, double precioTotal,
                  Instant fecha) {
        this.idCliente = idCliente;
        this.direccionEnvio = direccionEnvio;
        this.lineas = List.copyOf(lineas);
        this.precioTotal = precioTotal;
        this.fecha = fecha;
        this.estado = EstadoPedido.PAGADO;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public double getPrecioTotal() {
        return precioTotal;
    }

    public Instant getFecha() {
        return fecha;
    }

    public DireccionEnvio getDireccionEnvio() {
        return direccionEnvio;
    }

    public List<LineaPedido> getLineas() {
        return lineas;
    }

    public Optional<LineaPedido> buscarLinea(String idProducto) {
        return lineas.stream().filter(l -> l.getIdProducto().equals(idProducto)).findFirst();
    }

    /** Solo se puede cancelar mientras esta pagado y aun no se ha enviado. */
    public boolean sePuedeCancelar() {
        return estado == EstadoPedido.PAGADO;
    }

    /** El cliente puede confirmar que lo ha recibido en cualquier momento mientras el pedido sigue en curso. */
    public boolean sePuedeConfirmarRecepcion() {
        return estado == EstadoPedido.PAGADO || estado == EstadoPedido.ENVIADO || estado == EstadoPedido.ENTREGADO;
    }

    public void cancelar() {
        this.estado = EstadoPedido.CANCELADO;
    }

    public void finalizar() {
        this.estado = EstadoPedido.FINALIZADO;
    }

    @Override
    public String toString() {
        return "Pedido[id=" + id + ", idCliente=" + idCliente + ", estado=" + estado + "]";
    }
}
