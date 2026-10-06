# Dispositivos testados

## T10 Pro

- Android 10;
- ABI usada: `arm64-v8a`;
- tela física: 1280 × 800 em modo paisagem;
- APK 0.2.0: aproximadamente 305 KB;
- memória sem SF2: aproximadamente 32,8 MB PSS;
- memória com o SF2 de teste de 532 KB: aproximadamente 33–34 MB PSS;
- stream AAudio: 48 kHz, buffer de 512 frames e zero xruns no teste inicial;
- carregamento de preset, disparo de C4 e restauração automática após reinício validados;
- acesso persistente à pasta `Documents/AudioEvolution/SoundFonts` validado;
- 11 bancos do Audio Evolution catalogados automaticamente;
- `07Hillsong Pad.sf2` (aproximadamente 37 MB) carregado e tocado com zero xruns;
- memória com `07Hillsong Pad.sf2`: aproximadamente 106 MB PSS;
- demonstração de “When the Saints Go Marching In” validada com até 6 vozes e zero xruns;
- teclado MIDI físico ainda não validado.

As medições variam conforme tamanho e complexidade do banco SF2, quantidade de
vozes, firmware e rota de áudio.
