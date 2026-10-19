// Migracion de MongoDB para la recuperacion de contrasena: anade la coleccion "tokens_recuperacion".
//
// NO BORRA NADA. Solo crea la coleccion nueva (o actualiza su validador si ya existe) y sus indices. No toca
// usuarios, categorias, productos ni ninguna otra coleccion. Se puede ejecutar todas las veces que haga falta: si ya
// esta aplicada, no cambia nada.
//
// Como ejecutarlo (con mongosh instalado), indicando SIEMPRE la base de datos en la URI:
//   mongosh "mongodb://localhost:27017/ESIBuy" --file scripts_auxiliares/migracion-recuperacion-contrasena.js
//   mongosh "<URI de Atlas con la base de datos ESIBuy>" --file scripts_auxiliares/migracion-recuperacion-contrasena.js
// O abrirlo en VS Code con la extension "MongoDB for VS Code" conectada a tu servidor y pulsar "Play".
//
// Antes de aplicarlo en una base compartida (Atlas), haz una copia de seguridad (snapshot de Atlas o mongodump).
//
// IMPORTANTE: la definicion de abajo debe ser identica a la de src/test/resources/mongo/init-esibuy.js (lo comprueba
// el test MigracionTokensRecuperacionMongoTest).

// --- Ajustes ---------------------------------------------------------------------------------------------

// Base de datos que usa el backend.
const BASE_DE_DATOS = 'ESIBuy';

// Cuanto se conserva un token DESPUES de caducar antes de que MongoDB lo borre solo (24 h). Asi un enlace ya usado
// que alguien reutilice al dia siguiente se sigue identificando como "ya usado" (alerta de seguridad).
const SEGUNDOS_DE_RETENCION_TRAS_CADUCAR = 86400;

// --- Definicion ------------------------------------------------------------------------------------------

const NOMBRE = 'tokens_recuperacion';

const validador = {
   $jsonSchema: {
      bsonType: 'object',
      required: [ 'idUsuario', 'hashToken', 'estado', 'creadoEn', 'caducaEn' ],
      additionalProperties: false, // por ejemplo, impide guardar un campo "token" con el valor en claro
      properties: {
         _id: { bsonType: 'objectId' },
         idUsuario: { bsonType: 'objectId' },
         hashToken: { bsonType: 'string', pattern: '^[0-9a-f]{64}$' }, // SHA-256 en hexadecimal minuscula
         estado: { enum: [ 'VIGENTE', 'USADO', 'INVALIDADO' ] },
         creadoEn: { bsonType: 'date' },
         caducaEn: { bsonType: 'date' },
         usadoEn: { bsonType: 'date' }
      }
   }
};

// --- Aplicacion ------------------------------------------------------------------------------------------

db = db.getSiblingDB(BASE_DE_DATOS);
print('Base de datos: ' + db.getName());

if (db.getCollectionNames().includes(NOMBRE)) {
   // Ya existe: se actualiza su validador (collMod lo sustituye entero; no modifica los documentos que haya)
   db.runCommand({ collMod: NOMBRE, validator: validador });
   print('La coleccion ' + NOMBRE + ' ya existia: validador actualizado.');
} else {
   db.createCollection(NOMBRE, { validator: validador });
   print('Coleccion ' + NOMBRE + ' creada.');
}

const tokens = db.getCollection(NOMBRE);

// El hash del token es unico y es por lo que se busca
tokens.createIndex({ hashToken: 1 }, { unique: true });

// MongoDB borra el documento cuando pasa el tiempo indicado desde caducaEn
tokens.createIndex({ caducaEn: 1 }, { expireAfterSeconds: SEGUNDOS_DE_RETENCION_TRAS_CADUCAR });

// Un solo token VIGENTE por usuario, garantizado por la base de datos aunque dos solicitudes coincidan
tokens.createIndex({ idUsuario: 1, estado: 1 }, { unique: true, partialFilterExpression: { estado: 'VIGENTE' } });

print('Indices: ' + tokens.getIndexes().map(i => i.name).join(', '));
print('Migracion aplicada. No se ha modificado ningun otro dato.');
