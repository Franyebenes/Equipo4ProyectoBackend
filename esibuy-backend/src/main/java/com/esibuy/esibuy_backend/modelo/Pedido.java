package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Pedido de un cliente (coleccion "pedidos"). Se crea a partir del carrito y nace PAGADO (el pago es simulado).
 * Cada pedido es de un solo vendedor (idVendedor): si el carrito tiene productos de varios, el cliente elige de cual
 * compra. El stock ya quedo reservado al meter los productos en el carrito, asi que solo se devuelve si se cancela.
 */
@Document(collection = "pedidos")
public class Pedido {

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field(name = "idVendedor", targetType = FieldType.OBJECT_ID)
    private String idVendedor;
    @Field("estado")
    private EstadoPedido estado;
    @Field("precioTotal")
    private double precioTotal;
    @Field("fecha")
    private Instant fecha;
    // Ultima vez que cambio el pedido (su estado). En los pedidos anteriores a este campo no existe
    @Field("fechaActualizacion")
    private Instant fechaActualizacion;
    // Estado del envio: cada estado por el que ha pasado el pedido, con su fecha, y la fecha de entrega que indico el
    // vendedor al enviarlo (si no la indico, se calcula). Los pedidos anteriores a estos campos no los tienen
    @Field("seguimiento")
    private List<EventoSeguimiento> seguimiento;
    @Field("fechaEntregaEstimada")
    private Instant fechaEntregaEstimada;
    @Field("direccionEnvio")
    private DireccionEnvio direccionEnvio;
    @Field("lineas")
    private List<LineaPedido> lineas;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Pedido() {
    }

    public Pedido(String idCliente, String idVendedor, DireccionEnvio direccionEnvio, List<LineaPedido> lineas,
                  double precioTotal, Instant fecha) {
        this.idCliente = idCliente;
        this.idVendedor = idVendedor;
        this.direccionEnvio = direccionEnvio;
        this.lineas = List.copyOf(lineas);
        this.precioTotal = precioTotal;
        this.fecha = fecha;
        this.fechaActualizacion = fecha;
        this.estado = EstadoPedido.PAGADO;
        this.seguimiento = new ArrayList<>(List.of(new EventoSeguimiento(EstadoPedido.PAGADO, fecha)));
    }

    /** Sin vendedor: se mantiene para el codigo y los tests anteriores. */
    public Pedido(String idCliente, DireccionEnvio direccionEnvio, List<LineaPedido> lineas, double precioTotal,
                  Instant fecha) {
        this(idCliente, null, direccionEnvio, lineas, precioTotal, fecha);
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public String getIdVendedor() {
        return idVendedor;
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

    /** Ultima vez que cambio el pedido. Si es anterior a guardar este dato, la fecha en que se hizo. */
    public Instant getFechaActualizacion() {
        return fechaActualizacion != null ? fechaActualizacion : fecha;
    }

    /** Estados por los que ha pasado, del primero al ultimo. Vacio en los pedidos anteriores a guardarlos. */
    public List<EventoSeguimiento> getSeguimiento() {
        return seguimiento == null ? List.of() : Collections.unmodifiableList(seguimiento);
    }

    /** Fecha de entrega que indico el vendedor, o null si no indico ninguna. */
    public Instant getFechaEntregaEstimada() {
        return fechaEntregaEstimada;
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

    /** Se puede cancelar mientras aun no se ha enviado: pagado o en preparacion. */
    public boolean sePuedeCancelar() {
        return estado == EstadoPedido.PAGADO || estado == EstadoPedido.EN_PREPARACION;
    }

    /** El cliente puede confirmar que lo ha recibido en cualquier momento mientras el pedido sigue en curso. */
    public boolean sePuedeConfirmarRecepcion() {
        return estado.esPasoDelEnvio();
    }

    public void cancelar(Instant cuando) {
        cambiarA(EstadoPedido.CANCELADO, cuando);
    }

    public void finalizar(Instant cuando) {
        cambiarA(EstadoPedido.FINALIZADO, cuando);
    }

    // Cambia el estado y lo anota en el seguimiento con su fecha
    private void cambiarA(EstadoPedido nuevo, Instant cuando) {
        this.estado = nuevo;
        this.fechaActualizacion = cuando;
        if (seguimiento == null) {
            seguimiento = new ArrayList<>();
        }
        seguimiento.add(new EventoSeguimiento(nuevo, cuando));
    }

    @Override
    public String toString() {
        return "Pedido[id=" + id + ", idCliente=" + idCliente + ", idVendedor=" + idVendedor + ", estado=" + estado
                + "]";
    }
}