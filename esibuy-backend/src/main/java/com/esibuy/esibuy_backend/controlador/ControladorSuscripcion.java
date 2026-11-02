package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PerfilDTO;
import com.esibuy.esibuy_backend.dto.PrecioPremiumDTO;
import com.esibuy.esibuy_backend.dto.RespuestaBajaPremiumDTO;
import com.esibuy.esibuy_backend.dto.RespuestaPremiumDTO;
import com.esibuy.esibuy_backend.dto.SolicitudPremiumDTO;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ResultadoBajaPremium;
import com.esibuy.esibuy_backend.servicio.ResultadoPremium;
import com.esibuy.esibuy_backend.servicio.ServicioSuscripcion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Suscripcion Premium con pago simulado. Solo clientes y premium, siempre sobre el usuario de la sesion. */
@RestController
@RequestMapping("/api/cliente/suscripcion/premium")
@PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
public class ControladorSuscripcion {

    private final ServicioSuscripcion servicio;
    private final EstablecedorSesion establecedorSesion;

    public ControladorSuscripcion(ServicioSuscripcion servicio, EstablecedorSesion establecedorSesion) {
        this.servicio = servicio;
        this.establecedorSesion = establecedorSesion;
    }

    @GetMapping("/precio")
    public PrecioPremiumDTO precio() {
        return new PrecioPremiumDTO(servicio.precio());
    }

    // Pagar con una tarjeta guardada y pasar a Premium. Desde esta misma respuesta la sesion tiene ROLE_PREMIUM
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RespuestaPremiumDTO pasarAPremium(@Valid @RequestBody SolicitudPremiumDTO solicitud,
                                             Authentication autenticacion, HttpServletRequest peticion) {
        ResultadoPremium resultado = servicio.pasarAPremium(autenticacion.getName(), solicitud.idMetodoPago());
        establecedorSesion.actualizar(peticion, resultado.usuario());
        return new RespuestaPremiumDTO(PerfilDTO.desde(resultado.usuario()), resultado.pago().getImporte(),
                resultado.pago().getUltimosDigitos(), resultado.pago().getFecha());
    }

    // Volver al plan estandar
    @DeleteMapping
    public RespuestaBajaPremiumDTO volverAEstandar(Authentication autenticacion, HttpServletRequest peticion) {
        ResultadoBajaPremium resultado = servicio.volverAEstandar(autenticacion.getName());
        establecedorSesion.actualizar(peticion, resultado.usuario());
        return new RespuestaBajaPremiumDTO(PerfilDTO.desde(resultado.usuario()), resultado.productosQuitados());
    }
}