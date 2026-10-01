package com.esibuy.esibuy_backend.servicio;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.CategoriaDAO;

@Service
public class CategoriaService {

    private final CategoriaDAO catDAO;

    public CategoriaService(CategoriaDAO catDAO) {
        this.catDAO = catDAO;
    }

    @PreAuthorize("hasRole('ADMIN')") // seguridad extra, ya comprobada en el controlador
    public CategoriaDTO crearCategoria(CategoriaDTO dto) {
        String nombre = dto.getNombre().trim();

        if (catDAO.existsByNombreIgnoreCase(nombre)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe una categoría con ese nombre");
        }

        try {
            Categoria guardada = catDAO.save(new Categoria(nombre));
            return new CategoriaDTO(guardada.getNombre());
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se ha podido crear la categoría, inténtalo de nuevo más tarde", e);
        }
    }
}