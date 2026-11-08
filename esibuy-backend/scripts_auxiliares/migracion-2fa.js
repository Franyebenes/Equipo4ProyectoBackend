// Migracion de MongoDB para el segundo factor (2FA con TOTP): anade el subdocumento "dosFactores" al esquema de la
// coleccion "usuarios".
//
// NO BORRA NI MODIFICA NINGUN DOCUMENTO. Solo amplia el validador de la coleccion para que acepte (y valide) el campo
// nuevo. Las cuentas que ya existen no tienen ese campo y siguen siendo validas tal cual: todas quedan con un solo factor
// hasta que su dueno active el 2FA. No toca indices ni ninguna otra coleccion. Se puede ejecutar todas las veces que haga
// falta: si ya esta aplicada, no cambia nada.
//
// El script NO sustituye el validador por uno propio: lee el que tiene la coleccion ahora mismo y le anade la propiedad
// dosFactores, de modo que conserva el resto de reglas que tenga esa base de datos (que pueden no ser exactamente las de
// init-esibuy.js).
//
// Como ejecutarlo (con mongosh instalado), indicando SIEMPRE la base de datos en la URI:
//   mongosh "mongodb://localhost:27017/ESIBuy" --file scripts_auxiliares/migracion-2fa.js
//   mongosh "<URI de Atlas con la base de datos ESIBuy>" --file scripts_auxiliares/migracion-2fa.js
// O abrirlo en VS Code con la extension "MongoDB for VS Code" conectada a tu servidor y pulsar "Play".
//
// Antes de aplicarlo en una base compartida (Atlas), haz una copia de seguridad (snapshot de Atlas o mongodump).
//
// IMPORTANTE: la definicion de abajo debe ser identica a la de la propiedad dosFactores de src/test/resources/mongo/init-esibuy.js
// (lo comprueba el test MigracionDosFactoresMongoTest).

// --- Ajustes ---------------------------------------------------------------------------------------------

// Base de datos que usa el backend.
const BASE_DE_DATOS = 'ESIBuy';

// --- Definicion ------------------------------------------------------------------------------------------

const NOMBRE = 'usuarios';

// Texto cifrado del secreto: "version:base64url" (lo produce CifradorSecretos). Un secreto en claro, por ejemplo un
// Base32, no encaja en este patron.
const PATRON_SECRETO_CIFRADO = '^[0-9]{1,9}:[A-Za-z0-9_-]+$';

// Hash SHA-256 en hexadecimal minuscula: un codigo de recuperacion en claro no encaja en este patron.
const PATRON_HASH = '^[0-9a-f]{64}$';

const dosFactores = {
   bsonType: 'object',
   required: [ 'activo' ],
   additionalProperties: false, // por ejemplo, impide guardar un campo "secreto" con el valor en claro
   properties: {
      activo: { bsonType: 'bool' },
      secretoCifrado: { bsonType: 'string', pattern: PATRON_SECRETO_CIFRADO },
      // Activacion empezada y aun sin confirmar con el primer codigo
      pendiente: {
         bsonType: 'object',
         required: [ 'secretoCifrado', 'creadoEn' ],
         additionalProperties: false,
         properties: {
            secretoCifrado: { bsonType: 'string', pattern: PATRON_SECRETO_CIFRADO },
            creadoEn: { bsonType: 'date' }
         }
      },
      // Ultimo paso de tiempo (30 s) aceptado: impide reutilizar un codigo TOTP. Cabe en un entero de 64 bits.
      ultimoPaso: { bsonType: [ 'int', 'long' ], minimum: 0 },
      activadoEn: { bsonType: 'date' },
      codigosRecuperacion: {
         bsonType: 'array',
         maxItems: 20,
         items: {
            bsonType: 'object',
            required: [ 'hash', 'usado' ],
            additionalProperties: false,
            properties: {
               hash: { bsonType: 'string', pattern: PATRON_HASH },
               usado: { bsonType: 'bool' },
               usadoEn: { bsonType: 'date' }
            }
         }
      }
   },
   // Un 2FA activo siempre tiene secreto, fecha de activacion y codigos de recuperacion
   anyOf: [
      { properties: { activo: { enum: [ false ] } } },
      { required: [ 'secretoCifrado', 'activadoEn', 'codigosRecuperacion' ], properties: { activo: { enum: [ true ] } } }
   ]
};

// --- Aplicacion ------------------------------------------------------------------------------------------

db = db.getSiblingDB(BASE_DE_DATOS);
print('Base de datos: ' + db.getName());

const informacion = db.getCollectionInfos({ name: NOMBRE })[0];
if (!informacion) {
   throw new Error('No existe la coleccion ' + NOMBRE + ' en la base de datos ' + BASE_DE_DATOS
      + ': comprueba la URI de conexion. No se ha cambiado nada.');
}

// El validador que ya tiene la coleccion (o uno vacio si no tenia ninguno)
const validadorActual = (informacion.options && informacion.options.validator) || {};
const esquema = validadorActual.$jsonSchema || { bsonType: 'object' };
esquema.properties = esquema.properties || {};

// Se anade (o se vuelve a escribir igual) la propiedad nueva; el resto del validador queda como estaba
esquema.properties.dosFactores = dosFactores;
const validadorNuevo = Object.assign({}, validadorActual, { $jsonSchema: esquema });

// collMod sustituye el validador entero pero no modifica los documentos que haya
db.runCommand({ collMod: NOMBRE, validator: validadorNuevo });

print('Validador de ' + NOMBRE + ' actualizado: ahora incluye dosFactores.');
print('Migracion aplicada. No se ha modificado ningun documento.');
