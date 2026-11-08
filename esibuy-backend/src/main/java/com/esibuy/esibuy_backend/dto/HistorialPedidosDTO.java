package com.esibuy.esibuy_backend.dto;

import java.util.List;

/** Historial de compras del cliente: los pedidos en curso y los ya terminados (finalizados o cancelados). */
public record HistorialPedidosDTO(List<PedidoDTO> enProceso, List<PedidoDTO> historicos) {
}
