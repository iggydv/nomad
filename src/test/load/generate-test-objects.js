const fs = require('fs');
const faker = require('faker');

const writeUsers = fs.createWriteStream('test-objects-gr10.csv');
// writeUsers.write('id,creationTime,ttl\n', 'utf8');

function writeData(writer, encoding, callback) {
    let i = 60000;

    function write() {
        let ok = true;
        do {
            i -= 1;
            const objectId = faker.random.alphaNumeric(8);
            const creationTime = 1621163649;
            const ttl = 600;
            const data = `${objectId},${creationTime},${ttl}\n`;
            if (i === 0) {
                writer.write(data, encoding, callback);
            } else {
                // see if we should continue, or wait
                // don't pass the callback, because we're not done yet.
                ok = writer.write(data, encoding);
            }
        } while (i > 0 && ok);
        if (i > 0) {
            // had to stop early!
            // write some more once it drains
            writer.once('drain', write);
        }
    }

    write();
}

writeData(writeUsers, 'utf-8', () => {
    writeUsers.end();
});