package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("NfeSequenciaService — controle atômico de nNF por série")
public class NfeSequenciaServiceTest {

    private NfeSequenciaMapper mapper;
    private NfeSequenciaService service;

    private static final String CNPJ  = "54393421000159";
    private static final String SERIE = "1";

    @BeforeEach
    void setUp() {
        mapper  = mock(NfeSequenciaMapper.class);
        service = new NfeSequenciaServiceImpl(mapper);
    }

    @Test
    @DisplayName("Deve retornar 1 e inserir novo registro quando série ainda não existe")
    void primeiroNumeroInsereRegistro() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        int resultado = service.proximoNumero(CNPJ, SERIE);

        assertEquals(1, resultado);
        verify(mapper).inserir(argThat(seq ->
                seq.getCnpjEmitente().equals(CNPJ) &&
                seq.getSerie().equals(SERIE) &&
                seq.getUltimoNumero() == 1));
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("Deve incrementar e retornar próximo número quando série já existe (ultimo=5 → retorna 6)")
    void incrementaNumeroExistente() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(5);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        int resultado = service.proximoNumero(CNPJ, SERIE);

        assertEquals(6, resultado);
        verify(mapper).atualizarNumero(argThat(seq -> seq.getUltimoNumero() == 6));
        verify(mapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Deve respeitar série diferente como contador independente")
    void seriesIndependentes() {
        NfeSequencia s1 = new NfeSequencia();
        s1.setCnpjEmitente(CNPJ);
        s1.setSerie("1");
        s1.setUltimoNumero(10);

        NfeSequencia s2 = new NfeSequencia();
        s2.setCnpjEmitente(CNPJ);
        s2.setSerie("2");
        s2.setUltimoNumero(3);

        when(mapper.buscarParaAtualizar(CNPJ, "1")).thenReturn(s1);
        when(mapper.buscarParaAtualizar(CNPJ, "2")).thenReturn(s2);

        assertEquals(11, service.proximoNumero(CNPJ, "1"));
        assertEquals(4,  service.proximoNumero(CNPJ, "2"));
    }
}
