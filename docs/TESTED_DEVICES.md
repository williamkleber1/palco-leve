# Dispositivos testados

## T10 PRO

- Android 10;
- ABI usada: `arm64-v8a`;
- tela física: 1280 × 800 em modo paisagem;
- APK 0.6.2: aproximadamente 317 KB;
- memória sem SF2: aproximadamente 32,8 MB PSS;
- memória com o SF2 de teste de 532 KB: aproximadamente 33–34 MB PSS;
- stream AAudio: 48 kHz, buffer de 512 frames e zero xruns no teste inicial;
- carregamento de preset, disparo de C4 e restauração automática após reinício validados;
- acesso persistente à pasta `Documents/AudioEvolution/SoundFonts` validado;
- 11 bancos do Audio Evolution catalogados automaticamente;
- `07Hillsong Pad.sf2` (aproximadamente 37 MB) carregado e tocado com zero xruns;
- memória com `07Hillsong Pad.sf2`: aproximadamente 106 MB PSS;
- demonstração de “When the Saints Go Marching In” validada com até 6 vozes e zero xruns;
- cinco tracks simultâneas validadas com Bells Pad, Shimmer Pad, GeneralUser,
  Hillsong Pad e Ambient Pad;
- cinco tracks: aproximadamente 442 MB PSS, 22 vozes durante a demonstração e zero xruns;
- interface em tracks e seleção direta de SoundFont por track validadas em 1280 × 800;
- arranjo gospel complexo com acordes, baixo, arpejo e melodia validado nas cinco tracks;
- conjunto pesado incluindo `04Nord Grand Imperial.sf2`: aproximadamente 587 MB PSS;
- teclado físico `GZUT USB MIDI` reconhecido e validado tocando ao vivo;
- política da versão 0.6.1 validada auditivamente: ao saturar a polifonia, as
  notas novas continuam entrando e substituem as vozes mais antigas;
- pico observado durante execução manual pesada: 151 vozes de um teto de 160;
- nesse pico, o processo ficou entre 45% e 75% de um núcleo segundo o `top`;
- conjunto pesado em execução: aproximadamente 652 MB RSS, 10 MB de swap do
  processo e aproximadamente 583 MB PSS em medição anterior;
- durante a observação pesada, o contador passou de 12 para 15 underruns; uma
  das três falhas ocorreu em um intervalo limpo de 15 segundos com 151 vozes,
  e as outras medições incluíram a sobrecarga do próprio diagnóstico;
- modo adaptativo 32 → 24 da versão 0.6.2 validado por teste nativo e instalado
  no aparelho; o novo stream iniciou em 48 kHz, buffer 512, cinco camadas,
  `auto 32` e zero underruns;
- passe limpo do arranjo completo: zero xruns; a coleta simultânea de diagnóstico
  provocou 3 xruns e por isso não representa o uso normal;
- ADB via Wi-Fi validado em Android 10 após ativação inicial por USB.

As medições variam conforme tamanho e complexidade do banco SF2, quantidade de
vozes, firmware e rota de áudio.
