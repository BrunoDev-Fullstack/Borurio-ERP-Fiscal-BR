CREATE TABLE IF NOT EXISTS cliente (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       nome VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL,
    telefone VARCHAR(20),
    estado INT DEFAULT 1,
    criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
