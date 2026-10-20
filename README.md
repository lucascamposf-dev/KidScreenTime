# Tempo de Tela — Controle Parental (v0.2)

Ciclo: **60 min de tela → bloqueio de 30 min → só libera com atividade feita E pausa terminada.**
A pausa conta a partir do momento do bloqueio. Tudo configurável na tela dos pais.

## O que mudou na v0.2
- Bloqueio agora é uma **janela sobreposta** (`LockOverlay.kt`): fica por cima de qualquer app, mesmo com Home/Recentes.
  (Antes era uma Activity aberta em segundo plano — o Android 10+ bloqueia isso, o bloqueio nunca apareceria.)
- Tempo só conta com tela **ligada e destravada**.
- Atividades de **múltipla escolha** (3 botões, sem teclado), botão 🔊 Ouvir (voz em pt-BR) e vários acertos (padrão 3).
- **IA do Groq** (`GroqClient.kt`): gera perguntas de letras, palavras, animais, cores etc. A fila fica salva no
  aparelho (funciona offline). Sem internet/chave -> continhas locais.
- **PIN dos pais**: protege a tela de configuração e libera o celular na tela de bloqueio ("Área dos pais").
- Limites configuráveis (acabou o "1 minuto de teste" fixo no código) + botões "Testar bloqueio" e "Liberar agora".
- Serviço religa no boot e após atualizar o app.

## Configurar o Groq
1. Crie a chave grátis em https://console.groq.com/keys
2. No `local.properties` (raiz do projeto) adicione: `GROQ_API_KEY=gsk_xxxxxxxx`
3. Gere o APK de novo. (A chave fica embutida no APK — ok para uso pessoal, não publique o APK nem o local.properties.)

## Gerar o APK (Android Studio)
`Build > Build APK(s)` -> `app/build/outputs/apk/debug/app-debug.apk`
ou no PowerShell: `.\gradlew.bat assembleDebug`

## Instalar e configurar no celular do filho (nesta ordem)
1. Instale o APK (permita "instalar apps desconhecidos"; se o Play Protect avisar, escolha "instalar mesmo assim").
2. Abra o app -> crie o **PIN**.
3. Botão 1: permitir **exibir sobre outros apps** (obrigatório).
4. Botão 2: bateria **sem restrição**. Em Xiaomi/Samsung/Motorola também ative "Início automático"/"Sem restrições" nas configurações do app.
5. Botão 3: notificações. Botão 4: iniciar controle.
6. Toque em **Gerar atividades com IA** e depois em **Testar bloqueio agora** para validar.
7. Ajuste as regras (60/30/3) e salve. Teste o ciclo real antes de entregar o celular.

## Limitações (honestas)
- É bloqueio "soft": uma criança de 6 anos não burla, mas alguém que saiba abrir Configurações pode forçar a parada
  do app ou revogar a permissão, ou desinstalar. Para blindar de verdade: Device Owner/Lock Task (ou Google Family Link
  como segunda camada).
- Mudar a data/hora do aparelho pode encurtar a pausa.
- Código novo não foi compilado/testado em aparelho real — rode o checklist do passo 6/7.
