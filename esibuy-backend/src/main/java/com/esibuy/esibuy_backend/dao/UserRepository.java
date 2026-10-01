package com.esibuy.esibuy_backend.dao;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.esibuy.esibuy_backend.model.User;

public interface UserRepository extends MongoRepository<User, String> {
}