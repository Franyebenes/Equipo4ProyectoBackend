// 1. Seleccionar la base de datos
db = db.getSiblingDB("ESIBuy");

// 2. Limpieza colecciones previas al quedar obsoletas
db.users.drop();
db.categories.drop();
db.products.drop();
db.orders.drop();
db.reviews.drop();
db.incidents.drop();

// ==========================================
// 1. COLECCIÓN: USERS 
// ==========================================
db.createCollection("users", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "email", "passwordHash", "roles", "profile" ],
         properties: {
            email: { bsonType: "string" },
            passwordHash: { bsonType: "string" },
            status: { enum: [ "ACTIVE", "BLOCKED" ] },
            roles: {
               bsonType: "array",
               items: { bsonType: "string", enum: [ "ADMIN", "SELLER", "CUSTOMER", "PREMIUM" ] },
               minItems: 1
            },
            profile: {
               bsonType: "object",
               required: ["firstName", "lastName"], 
               properties: {
                  firstName: { bsonType: "string" },
                  lastName: { bsonType: "string" },
                  dni: { bsonType: "string" },
                  birthDate: { bsonType: "date" }, 
                  phone: { 
                     bsonType: "string",
                     pattern: "^[0-9]{9}$" // Exige 9 números exactos
                  },
                  avatarUrl: { bsonType: "string" },
                  tradeName: { bsonType: "string" }, 
                  mainCategoryId: { bsonType: "objectId" },
                  officeLocation: { bsonType: "string" }, 
                  joinDate: { bsonType: "date" } 
               }
            }
         }
      }
   }
});

db.users.createIndex({ "email": 1 }, { unique: true });
db.users.createIndex({ "profile.tradeName": 1 }, { unique: true, partialFilterExpression: { "profile.tradeName": { $exists: true } } });

// ==========================================
// 2. COLECCIÓN: CATEGORIES
// ==========================================
db.createCollection("categories", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "name" ],
         properties: {
            name: { bsonType: "string" }
         }
      }
   }
});

// ==========================================
// 3. COLECCIÓN: PRODUCTS
// ==========================================
db.createCollection("products", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "sellerId", "categoryIds", "name", "pricing", "stock", "active" ],
         properties: {
            sellerId: { bsonType: "objectId" },
            name: { bsonType: "string" },
            description: { bsonType: "string" },
            imageUrl: { bsonType: "string" },
            categoryIds: { 
               bsonType: "array",
               items: { bsonType: "objectId" },
               minItems: 1 
            },
            pricing: {
               bsonType: "object",
               required: [ "basePrice" ],
               properties: {
                  basePrice: { bsonType: "number", minimum: 0 },
                  discount: { bsonType: "number", minimum: 0, maximum: 100 }, 
                  premiumDiscount: { bsonType: "number", minimum: 0, maximum: 100 } 
               }
            },
            stock: { bsonType: "int", minimum: 0 },
            active: { bsonType: "bool" }
         }
      }
   }
});

db.products.createIndex({ "sellerId": 1 });
db.products.createIndex({ "categoryIds": 1 });

// ==========================================
// 4. COLECCIÓN: ORDERS
// ==========================================
db.createCollection("orders", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "customerId", "status", "totalAmount", "date", "shippingAddress", "items" ],
         properties: {
            customerId: { bsonType: "objectId" },
            status: {
               enum: [ "PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED" ]
            },
            totalAmount: { bsonType: "number", minimum: 0 },
            date: { bsonType: "date" },
            items: {
               bsonType: "array",
               minItems: 1, 
               items: {
                  bsonType: "object",
                  required: ["productId", "quantity", "unitPrice"]
               }
            }
         }
      }
   }
});

db.orders.createIndex({ "customerId": 1 });

// ==========================================
// 5. COLECCIÓN: REVIEWS
// ==========================================
db.createCollection("reviews", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "productId", "customerId", "orderId", "rating" ],
         properties: {
            productId: { bsonType: "objectId" },
            customerId: { bsonType: "objectId" },
            orderId: { bsonType: "objectId" },
            rating: {
               bsonType: "int",
               minimum: 1,
               maximum: 5 
            }
         }
      }
   }
});

db.reviews.createIndex({ "customerId": 1, "productId": 1, "orderId": 1 }, { unique: true });

// ==========================================
// 6. COLECCIÓN: INCIDENTS
// ==========================================
db.createCollection("incidents", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "userId", "description", "status" ],
         properties: {
            userId: { bsonType: "objectId" },
            status: {
               enum: [ "OPEN", "IN_PROGRESS", "RESOLVED" ]
            }
         }
      }
   }
});