package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dao.UserRepository;
import com.esibuy.esibuy_backend.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<User> obtenerTodos() {
        return userRepository.findAll();
    }

    public List<User> obtenerPorRol(String rol) {
        return userRepository.findByRolContaining(rol);
    }

    public User actualizar(String id, User datos) {
        User existente = buscar(id);
        existente.setPerfil(datos.getPerfil());
        existente.setRol(datos.getRol());
        // el email y la contraseña no se cambian
        return userRepository.save(existente);
    }

    public User cambiarEstado(String id, String estado) {
        User existente = buscar(id);
        existente.setEstado(estado);
        return userRepository.save(existente);
    }

    public void eliminar(String id) {
        buscar(id);
        userRepository.deleteById(id);
    }

    private User buscar(String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }
}