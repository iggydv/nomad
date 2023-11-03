package org.nomad.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(force = true)
public class WorldConfiguration {
    double height;
    double width;
}
