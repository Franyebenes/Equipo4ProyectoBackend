// Playground de MongoDB para VS Code: pone al dia las reglas de validacion de la coleccion "usuarios" con las del
// proyecto (src/test/resources/mongo/init-esibuy.js), SIN borrar datos ni indices.
//
// Para que sirve: si la base se creo con una version antigua del script, el esquema puede no admitir valores nuevos.
// Por ejemplo, el registro guarda las cuentas nuevas con estado DESACTIVADO, y un esquema antiguo que solo admite
// ACTIVO y BLOQUEADO las rechaza con "Document failed validation" (codigo 121).
//
// Como usarlo:
//   1. Conecta la extension "MongoDB for VS Code" al servidor que usa el backend.
//   2. Ejecuta este fichero tal cual (boton "Play"): SOLO muestra lo que hay, no cambia nada.
//   3. Si los estados actuales no coinciden con los esperados, pon APLICAR = true y vuelve a ejecutarlo.
//
// Solo cambia las reglas que se aplican a las proximas escrituras (collMod). Los documentos que ya existen no se
// modifican. NO uses init-esibuy.js para esto: ese script borra las colecciones.

// --- Ajustes ---------------------------------------------------------------------------------------------

const BASE_DE_DATOS = 'ESIBuy';

// false = solo comprueba y muestra. true = actualiza el esquema de "usuarios".
const APLICAR = false;

// --- Esquema esperado (el mismo que init-esibuy.js) ----------------------------------------------------------

const esperado = {
  $jsonSchema: {
    bsonType: 'object',
    required: ['email', 'password', 'rol', 'perfil'],
    properties: {
      email: { bsonType: 'string' },
      password: { bsonType: 'string' },
      estado: { enum: ['ACTIVO', 'BLOQUEADO', 'DESACTIVADO'] },
      rol: {
        bsonType: 'array',
        items: { bsonType: 'string', enum: ['ADMIN', 'VENDEDOR', 'CLIENTE', 'PREMIUM'] },
        minItems: 1,
        maxItems: 1, // Un solo rol por usuario
      },
      perfil: {
        bsonType: 'object',
        required: ['nombre', 'apellidos'],
        properties: {
          nombre: { bsonType: 'string' },
          apellidos: { bsonType: 'string' },
          dni: { bsonType: 'string' },
          fechaNacimiento: { bsonType: 'date' },
          telefono: { bsonType: 'string', pattern: '^[0-9]{9}$' },
          imagen: { bsonType: 'string' },
          nombreComercial: { bsonType: 'string' },
          idCategoriaPrincipal: { bsonType: 'objectId' },
          sede: { bsonType: 'string' },
          fechaIncorporacion: { bsonType: 'date' },
        },
      },
    },
  },
};

// --- Comprobacion ----------------------------------------------------------------------------------------

use(BASE_DE_DATOS);

const info = db.getCollectionInfos({ name: 'usuarios' })[0];
const validadorActual = info && info.options ? info.options.validator : null;

const enumDe = (validador, campo) =>
  validador && validador.$jsonSchema && validador.$jsonSchema.properties && validador.$jsonSchema.properties[campo]
    ? validador.$jsonSchema.properties[campo].enum
    : null;

const estadosActuales = enumDe(validadorActual, 'estado');
const estadosEsperados = enumDe(esperado, 'estado');
const mismosEstados =
  Array.isArray(estadosActuales) && [...estadosActuales].sort().join() === [...estadosEsperados].sort().join();

let resultadoDeAplicar = 'No se ha aplicado nada (APLICAR = false o el esquema ya coincide).';
if (APLICAR) {
  if (!info) {
    resultadoDeAplicar = 'La coleccion "usuarios" no existe: crea la base con init-esibuy.js (solo si esta vacia).';
  } else if (mismosEstados) {
    resultadoDeAplicar = 'Los estados ya coinciden: no hay nada que actualizar.';
  } else {
    resultadoDeAplicar = db.runCommand({ collMod: 'usuarios', validator: esperado });
  }
}

// La ultima expresion es la que muestra el playground.
({
  baseDeDatos: BASE_DE_DATOS,
  coleccionExiste: Boolean(info),
  estadosActuales,
  estadosEsperados,
  coinciden: mismosEstados,
  resultadoDeAplicar,
  validadorActualCompleto: validadorActual,
});
