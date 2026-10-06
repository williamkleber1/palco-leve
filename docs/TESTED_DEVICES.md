# Dispositivos testados

## T10 Pro

- Android 10;
- ABI usada: `arm64-v8a`;
- tela física: 1280 × 800 em modo paisagem;
- APK 0.5.0: aproximadamente 313 KB;
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
- pico instrumentado observado: 44 vozes e aproximadamente 31% de CPU do processo;
- passe limpo do arranjo completo: zero xruns; a coleta simultânea de diagnóstico
  provocou 3 xruns e por isso não representa o uso normal;
- teclado MIDI físico ainda não validado.

As medições variam conforme tamanho e complexidade do banco SF2, quantidade de
vozes, firmware e rota de áudio.
