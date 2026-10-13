package com.esibuy.esibuy_backend.servicio;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decide la IP del cliente para los limites por IP. X-Forwarded-For solo se tiene en cuenta si la conexion viene de
 * un proxy de confianza; entonces se toma la direccion mas a la derecha que no sea de un proxy de confianza, porque
 * las de la izquierda las puede escribir el propio cliente. Si la cabecera falta o contiene algo que no es una IP,
 * se usa la IP de la conexion, asi nunca se propaga texto arbitrario a contadores ni logs.
 */
public class ResolutorIpCliente {

    private static final String CABECERA_REENVIO = "X-Forwarded-For";
    private static final Pattern FORMA_DE_IP = Pattern.compile(
            "^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|(?=[0-9a-fA-F:.]*:)[0-9a-fA-F:.]{2,45})$");

    private final Set<String> proxiesConfiables;

    public ResolutorIpCliente(List<String> proxiesConfiables) {
        this.proxiesConfiables = Set.copyOf(proxiesConfiables);
    }

    public String resolver(HttpServletRequest peticion) {
        String ipConexion = peticion.getRemoteAddr();
        String cabecera = peticion.getHeader(CABECERA_REENVIO);
        if (cabecera == null || !proxiesConfiables.contains(ipConexion)) {
            return ipConexion;
        }
        String[] direcciones = cabecera.split(",");
        for (int i = direcciones.length - 1; i >= 0; i--) {
            String direccion = direcciones[i].trim();
            if (!FORMA_DE_IP.matcher(direccion).matches()) {
                return ipConexion;
            }
            if (!proxiesConfiables.contains(direccion)) {
                return direccion;
            }
        }
        return ipConexion;
    }
}
