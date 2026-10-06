# Histórico de versões

## 0.6.2

- adiciona modo adaptativo de polifonia;
- inicia com 32 vozes por camada e reduz para 24 após o primeiro underrun;
- mostra o limite atual como `auto 32` ou `auto 24` no cabeçalho;
- mantém a prioridade das notas novas;
- adiciona teste do limite lógico menor que o pool físico.

## 0.6.1

- corrige notas novas que podiam não soar com a polifonia totalmente ocupada;
- passa a reutilizar primeiro vozes em release e depois as vozes mais antigas;
- adiciona teste automatizado de saturação do pool de vozes.

## 0.6.0

- adiciona arranjo gospel original para teste complexo das cinco tracks;
- mantém diagnóstico de taxa, buffer, underruns, camadas e vozes ativas.

## 0.5.0

- reorganiza a interface em tracks inspiradas em DAWs;
- move importação e escolha de pasta para a Biblioteca;
- permite selecionar banco e preset ao tocar em uma track.

## 0.4.0

- expande o motor para cinco camadas simultâneas;
- adiciona volume e liga/desliga por track.
