package com.esibuy.esibuy_backend.dao;

import com.esibuy.esibuy_backend.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface UserRepository extends MongoRepository<User, String> {
    List<User> findByRolContaining(String rol);
}