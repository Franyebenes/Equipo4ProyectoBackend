package com.esibuy.esibuy_backend.dto;

import java.util.List;
import com.esibuy.esibuy_backend.modelo.ListaProductos;

//  resumen para el menu de listas y para el boton "Anadir a lista". portadas: imagenes (base64) de los ultimos
//  productos guardados, para el mosaico de la coleccion; solo se rellena al listar, vacia en el resto de respuestas
public record ListaDTO(String id, String nombre, boolean porDefecto, List<String> idProductos, List<String> portadas) {

    public static ListaDTO desde(ListaProductos lista) {
        return desde(lista, List.of());
    }

    public static ListaDTO desde(ListaProductos lista, List<String> portadas) {
        return new ListaDTO(lista.getId(), lista.getNombre(), lista.isPorDefecto(), lista.getIdProductos(), portadas);
    }
}
