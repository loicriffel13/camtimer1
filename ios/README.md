# CamTimer iOS

Portage natif iOS de CamTimer Android. La branche Android d'origine reste inchangée.

## Fonctionnalités à reproduire
- Prévisualisation caméra arrière et enregistrement continu avec microphone
- Chronomètre configurable (0–99 min, 0–59 s), compte à rebours puis temps additionnel en vert
- Affichage grand format à 10, 5, 4, 3, 2, 1 secondes et « Départ » à zéro
- Alerte sonore paramétrable (fréquence, durée, seuil)
- Déclenchement automatique de l'enregistrement à un seuil réglable
- Incrustation permanente du chronomètre **dans la vidéo exportée**
- Enregistrement dans Photos

## État
La structure de portage et la vérification de compilation iOS sont initialisées. **L'application iOS complète n'est pas encore implémentée ni testée.** Ne pas confondre la réussite du build Android avec un build iOS.

## Compilation sur Windows
Le workflow `.github/workflows/ios.yml` utilise un runner macOS hébergé par GitHub. Il valide la présence de Xcode et les sources Swift du portage ; une archive IPA installable nécessite un projet Xcode complet, un compte Apple Developer et la configuration de signature.

## Différences iOS
iOS suspend normalement la capture vidéo lorsque l'application est en arrière-plan ou l'écran verrouillé. L'enregistrement continu n'est prévu que lorsque CamTimer est actif au premier plan.
