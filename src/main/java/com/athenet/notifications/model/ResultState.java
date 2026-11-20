package com.athenet.notifications.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Una fila por partido permite serializar revisiones y descartar mensajes antiguos. */
@Entity @Table(name = "newsletter_result_state") @Getter @Setter
public class ResultState {
    @Id private Long matchId;
    @Column(nullable = false) private int latestVersion;
    // También fuerza INSERT para un estado nuevo con ID asignado y evita que merge sobrescriba otra revisión.
    @Version private Long entityVersion;
}
