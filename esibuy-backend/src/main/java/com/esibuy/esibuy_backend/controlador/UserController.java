package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.model.User;
import com.esibuy.esibuy_backend.servicio.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:4200")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping
    public List<User> listarUsuarios(@RequestParam(required = false) String rol) {
        if (rol != null && !rol.isEmpty()) {
            return userService.obtenerPorRol(rol);
        }
        return userService.obtenerTodos();
    }

    @PutMapping("/{id}")
    public User actualizarUsuario(@PathVariable String id, @RequestBody User usuario) {
        return userService.actualizar(id, usuario);
    }

    @PutMapping("/{id}/bloquear")
    public User bloquearUsuario(@PathVariable String id) {
        return userService.cambiarEstado(id, "BLOQUEADO");
    }

    @PutMapping("/{id}/desbloquear")
    public User desbloquearUsuario(@PathVariable String id) {
        return userService.cambiarEstado(id, "ACTIVO");
    }

    @DeleteMapping("/{id}")
    public void eliminarUsuario(@PathVariable String id) {
        userService.eliminar(id);
    }
}