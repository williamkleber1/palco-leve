# Palco Leve

Instrumento Android minimalista para performance ao vivo com teclado MIDI e bancos SoundFont 2 (`.sf2`).

O objetivo é iniciar rápido, manter baixa latência e oferecer somente os controles necessários no palco. O projeto é original e não contém código, marca, interface ou conteúdo do Audio Evolution Mobile Studio.

## MVP

- motor nativo AAudio;
- sintetizador SF2 com TinySoundFont;
- teclado USB/Bluetooth MIDI pela API MIDI do Android;
- cinco camadas simultâneas, cada uma com banco, preset, volume e liga/desliga;
- interface em tracks inspirada em DAWs, com seleção do SF2 ao tocar na track;
- gerenciamento de pasta e importação concentrado no menu Biblioteca;
- catálogo de SF2 de uma pasta autorizada, incluindo a pasta do Audio Evolution;
- transpose, volume, sustain e pânico;
- demonstração musical com um spiritual tradicional em domínio público;
- arranjo gospel original para teste de polifonia e desempenho das cinco tracks;
- diagnóstico de stream, latência e underruns;
- funcionamento offline, sem anúncios ou telemetria.

## Princípios técnicos

- até cinco SoundFonts carregadas, com 32 vozes por camada e ganho normalizado;
- modo adaptativo de polifonia: começa com 32 vozes por camada e, se houver underrun, reduz para 24, sempre preservando as notas novas;
- callback de áudio nativo sem alocações, bloqueios ou acesso a disco;
- fila MIDI sem bloqueio no lado do áudio;
- stream AAudio de baixa latência, com modo exclusivo e fallback compartilhado;
- suporte a Android 8.0 ou superior em `arm64-v8a` e `armeabi-v7a`.

## Indicador do motor

O cabeçalho mostra uma linha semelhante a:

```text
48000 Hz • buffer 512 • falhas 0 • camadas 5 • vozes 86 • auto 32
```

- `falhas` é o contador de underruns do stream AAudio atual;
- `vozes` é a soma das vozes ativas nas camadas ligadas;
- `auto 32` indica o limite normal por camada;
- `auto 24` indica que o modo adaptativo detectou uma falha e reduziu a
  polifonia para proteger a continuidade do áudio.

O limite volta para 32 quando o stream de áudio é reiniciado. A explicação
completa do algoritmo está em [`docs/AUDIO_ENGINE.md`](docs/AUDIO_ENGINE.md).

## Compilar

O projeto abre normalmente no Android Studio. Para a compilação local mínima usada
durante o desenvolvimento, instale Android SDK 35, Build Tools 35.0.0, NDK
27.2.12479018 e JDK 17, e execute:

```sh
git submodule update --init --recursive
./build-manual.sh
```

O APK assinado para testes locais será criado em `dist/Palco-Leve-0.6.2.apk`.
A chave gerada é apenas de desenvolvimento e fica fora do Git.

Os resultados dos testes em hardware ficam em
[`docs/TESTED_DEVICES.md`](docs/TESTED_DEVICES.md).

O teste nativo da política de vozes pode ser executado no macOS ou Linux com:

```sh
clang++ -std=c++17 -O2 \
  -I third_party/TinySoundFont -I app/src/main/cpp \
  tests/voice_stealing_test.cpp -o /tmp/palco-leve-voice-test
/tmp/palco-leve-voice-test third_party/TinySoundFont/examples/florestan-subset.sf2
```

## Dependência

[TinySoundFont](https://github.com/schellingb/TinySoundFont) é incluído como
submódulo Git e usado sob sua licença MIT. Bancos `.sf2` não fazem parte do app.
