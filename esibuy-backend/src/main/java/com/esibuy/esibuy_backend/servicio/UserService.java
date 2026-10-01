package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.model.User;
import com.esibuy.esibuy_backend.dao.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<User> obtenerTodos() {
        return userRepository.findAll();
    }

    public User actualizar(String id, User usuarioActualizado) {
        usuarioActualizado.setId(id);
        return userRepository.save(usuarioActualizado);
    }

    public void eliminar(String id) {
        userRepository.deleteById(id);
    }
}