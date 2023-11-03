package org.nomad.pithos.components;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import org.nomad.grpc.superpeerservice.VirtualPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Scanner;

public class InputReader {

    public ArrayList<VirtualPosition> readMovements(int fileNumber) throws IOException {
        ArrayList<VirtualPosition> movements = new ArrayList<>();
        InputStream inputStream = null;
        Scanner sc = null;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            String filename;
            if (fileNumber <= 9) {
                filename = String.format("movements/player_0%d_movement.csv", fileNumber);
            } else {
                filename = String.format("movements/player_%d_movement.csv", fileNumber);
            }

            inputStream = loader.getResourceAsStream(filename);

            sc = new Scanner(inputStream, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                String[] xy = line.split(",");
                if (xy.length != 0) {
                    VirtualPosition movement = VirtualPosition.newBuilder()
                            .setX(Double.parseDouble(xy[1]))
                            .setY(Double.parseDouble(xy[2]))
                            .build();
                    movements.add(movement);
                }
            }
            // note that Scanner suppresses exceptions
            if (sc.ioException() != null) {
                throw sc.ioException();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {

            if (inputStream != null) {
                inputStream.close();
            }
            if (sc != null) {
                sc.close();
            }
        }
        return movements;
    }

    public ArrayList<VirtualPosition> readMovements() throws IOException {
        Scanner kbd = new Scanner(System.in);
        String decision;
        System.out.println("Please enter movement model number: ");
        decision = kbd.nextLine();
        int fileNumber = Integer.parseInt(decision);

        return readMovements(fileNumber);
    }
}
