package com.esibuy.esibuy_backend.servicio;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.DireccionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudDireccionDTO;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Direccion;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.repositorio.RepositorioDireccion;

/** Libreta de direcciones del cliente de la sesion . */
@Service
public class ServicioDireccion {

    static final int MAXIMO_DIRECCIONES = 10;
    static final String MENSAJE_NO_ENCONTRADA = "La direccion no existe";
    static final String CODIGO_EN_USO = "DIRECCION_EN_USO";
    static final String MENSAJE_EN_USO =
            "No puedes modificar ni eliminar una direccion usada en un pedido que todavia esta en curso";
    static final String CODIGO_LIMITE = "LIMITE_DIRECCIONES";

    private final RepositorioDireccion repositorio;
    private final ComprobadorUsoDireccion comprobadorUso;

    public ServicioDireccion(RepositorioDireccion repositorio, ComprobadorUsoDireccion comprobadorUso) {
        this.repositorio = repositorio;
        this.comprobadorUso = comprobadorUso;
    }

    public List<DireccionDTO> listar(String idUsuario) {
        return repositorio.buscarPorUsuario(new ObjectId(idUsuario)).stream().map(DireccionDTO::desde).toList();
    }

    public DireccionDTO obtener(String idUsuario, String idDireccion) {
        return DireccionDTO.desde(buscarPropia(idUsuario, idDireccion));
    }

    
    public DireccionDTO crear(String idUsuario, SolicitudDireccionDTO solicitud) {
        long existentes = repositorio.contarPorUsuario(new ObjectId(idUsuario));
        if (existentes >= MAXIMO_DIRECCIONES) {
            throw new ConflictoNegocioException(CODIGO_LIMITE,
                    "Como maximo puedes guardar " + MAXIMO_DIRECCIONES + " direcciones");
        }
        Direccion direccion = new Direccion(idUsuario);
        aplicar(direccion, solicitud);
        direccion.marcarPredeterminada(solicitud.predeterminada() || existentes == 0); // la primera siempre lo es

        Direccion guardada = repositorio.save(direccion);
        if (guardada.isPredeterminada()) {
            quitarPredeterminadaDeLasDemas(idUsuario, guardada.getId());
        }
        return DireccionDTO.desde(guardada);
    }

    //  No cambia la marca de predeterminada: para eso esta marcarPredeterminada
    public DireccionDTO modificar(String idUsuario, String idDireccion, SolicitudDireccionDTO solicitud) {
        Direccion direccion = buscarPropia(idUsuario, idDireccion);
        comprobarQueNoEstaEnUso(idUsuario, idDireccion);
        aplicar(direccion, solicitud);
        return DireccionDTO.desde(repositorio.save(direccion));
    }

    /** Se permite aunque la direccion este en un pedido: no cambia sus datos. */
    public DireccionDTO marcarPredeterminada(String idUsuario, String idDireccion) {
        Direccion direccion = buscarPropia(idUsuario, idDireccion);
        direccion.marcarPredeterminada(true);
        Direccion guardada = repositorio.save(direccion);
        quitarPredeterminadaDeLasDemas(idUsuario, guardada.getId());
        return DireccionDTO.desde(guardada);
    }

    public void eliminar(String idUsuario, String idDireccion) {
        Direccion direccion = buscarPropia(idUsuario, idDireccion);
        comprobarQueNoEstaEnUso(idUsuario, idDireccion);
        repositorio.delete(direccion);

        // Si se borra la predeterminada, pasa a serlo la siguiente (si queda alguna)
        if (direccion.isPredeterminada()) {
            repositorio.buscarPorUsuario(new ObjectId(idUsuario)).stream().findFirst().ifPresent(otra -> {
                otra.marcarPredeterminada(true);
                repositorio.save(otra);
            });
        }
    }

    /**
     * Copia de una direccion de la libreta para guardarla en un pedido (la usa ServicioPedido). Solo una del propio
     * cliente: la de otro da 404, igual que una que no existe. La copia lleva el id para poder bloquearla despues.
     */
    public DireccionEnvio paraPedido(String idUsuario, String idDireccion) {
        Direccion direccion = buscarPropia(idUsuario, idDireccion);
        return new DireccionEnvio(direccion.lineaCalle(), direccion.getCiudad(), direccion.getCodigoPostal(),
                direccion.getId());
    }

    private Direccion buscarPropia(String idUsuario, String idDireccion) {
        // Un id mal formado es "no existe", no un error 500
        if (idDireccion == null || !ObjectId.isValid(idDireccion)) {
            throw new RecursoNoEncontradoException(MENSAJE_NO_ENCONTRADA);
        }
        return repositorio.buscarPorIdYUsuario(new ObjectId(idDireccion), new ObjectId(idUsuario))
                .orElseThrow(() -> new RecursoNoEncontradoException(MENSAJE_NO_ENCONTRADA));
    }

    private void comprobarQueNoEstaEnUso(String idUsuario, String idDireccion) {
        if (comprobadorUso.estaEnPedidoEnCurso(idUsuario, idDireccion)) {
            throw new ConflictoNegocioException(CODIGO_EN_USO, MENSAJE_EN_USO);
        }
    }

    private void quitarPredeterminadaDeLasDemas(String idUsuario, String idPredeterminada) {
        // Como mucho hay 10 direcciones, asi que basta con leerlas todas
        List<Direccion> cambiadas = repositorio.buscarPorUsuario(new ObjectId(idUsuario)).stream()
                .filter(d -> d.isPredeterminada() && !d.getId().equals(idPredeterminada))
                .toList();
        cambiadas.forEach(d -> d.marcarPredeterminada(false));
        if (!cambiadas.isEmpty()) {
            repositorio.saveAll(cambiadas);
        }
    }

    private static void aplicar(Direccion direccion, SolicitudDireccionDTO s) {
        direccion.actualizarDatos(s.destinatario().strip(), s.calle().strip(), s.numero().strip(),
                vacioANull(s.pisoPuerta()), s.codigoPostal(), s.ciudad().strip(), s.provincia().strip());
    }

    private static String vacioANull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }
}