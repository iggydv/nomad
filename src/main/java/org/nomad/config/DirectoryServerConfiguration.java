package org.nomad.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(force = true)
public class DirectoryServerConfiguration {
    String path;
    String hostname;
}
