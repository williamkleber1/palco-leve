# Motor de áudio e polifonia

Este documento descreve o comportamento do motor nativo do Palco Leve 0.6.2,
com foco em previsibilidade durante apresentações ao vivo em tablets modestos.

## Fluxo de áudio

O aplicativo recebe eventos do teclado pela API MIDI do Android e os coloca em
uma fila para o callback nativo. O callback consome os eventos e mistura até
cinco instâncias do TinySoundFont em um stream AAudio estéreo.

O caminho de tempo real não faz alocações, não acessa disco e não adquire
mutexes. Cada SoundFont possui um conjunto de 32 estruturas de voz
pré-alocadas. As amostras dos bancos permanecem em memória enquanto a camada
estiver carregada.

Configuração usada no T10 PRO:

- saída estéreo em ponto flutuante;
- 48 kHz;
- modo AAudio de baixa latência;
- compartilhamento exclusivo, com fallback para compartilhado;
- buffer solicitado de dois bursts, observado como 512 frames no aparelho.

## Política de roubo de vozes

O TinySoundFont original reaproveita uma voz quando ela já está em release. Se
todas as vozes estiverem sustentadas, ele pode recusar regiões da nota nova.
Esse comportamento é ruim para execução ao vivo, pois uma tecla recém-tocada
pode não produzir som.

Antes de cada `note on`, o Palco Leve:

1. conta quantas regiões do preset responderão à nota e à velocidade;
2. calcula quantas vozes cabem no limite lógico atual;
3. reaproveita primeiro a voz em release de menor nível;
4. se necessário, substitui a voz sustentada da nota mais antiga;
5. em empate, escolhe a voz de menor nível para reduzir o corte audível;
6. depois do disparo, confirma que o número de vozes respeita o limite.

Assim, a nota nova tem prioridade. O número de estruturas pré-alocadas não
muda durante a reprodução.

## Modo adaptativo

O stream começa em `auto 32`, permitindo até 32 vozes por camada e até 160 nas
cinco camadas. A interface consulta uma vez por segundo o contador de underruns
do AAudio. Quando esse contador aumenta pela primeira vez:

- o limite lógico global passa imediatamente de 32 para 24 vozes por camada;
- o cabeçalho muda de `auto 32` para `auto 24`;
- as notas novas continuam com prioridade;
- o limite permanece em 24 até o stream ser reiniciado.

O teto seguro passa a ser 120 vozes nas cinco camadas. A redução não diminui de
forma relevante a RAM, porque a maior parte da memória é ocupada pelas amostras
dos SF2. O objetivo é reduzir o custo de síntese e devolver margem ao callback
de áudio.

## Leitura do diagnóstico

Exemplo:

```text
48000 Hz • buffer 512 • falhas 1 • camadas 5 • vozes 108 • auto 24
```

- `48000 Hz`: taxa real do stream;
- `buffer 512`: tamanho atual do buffer em frames;
- `falhas 1`: underruns acumulados desde a abertura do stream;
- `camadas 5`: SoundFonts carregadas;
- `vozes 108`: vozes ativas somadas nas camadas ligadas;
- `auto 24`: limite adaptativo atual por camada.

O contador de falhas é reiniciado quando o stream é fechado e aberto novamente.
Ferramentas como `top`, `dumpsys meminfo` e `uiautomator` também consomem CPU no
tablet e podem produzir falhas que não ocorreriam em uma apresentação normal.

## Recomendações para o T10 PRO

- cinco camadas são adequadas quando os SF2 são pequenos ou médios;
- com bancos grandes, como `04Nord Grand Imperial`, deixe o modo adaptativo
  cuidar da polifonia e evite executar ferramentas de diagnóstico no palco;
- se o indicador permanecer em `auto 24`, isso significa que o motor protegeu
  o áudio após detectar falta de tempo de processamento;
- use `PÂNICO` caso um dispositivo MIDI deixe notas presas;
- reiniciar o áudio restaura `auto 32`, portanto faça isso apenas quando quiser
  tentar novamente o limite máximo.

## Teste automatizado

`tests/voice_stealing_test.cpp` cobre dois casos:

- pool completamente ocupado: a nota nova substitui a mais antiga;
- pool físico de 32 vozes com limite lógico menor: o teto adaptativo é
  respeitado sem descartar a nota mais recente.

O teste usa o banco pequeno incluído nos exemplos do TinySoundFont e não exige
Android nem o tablet conectado.
