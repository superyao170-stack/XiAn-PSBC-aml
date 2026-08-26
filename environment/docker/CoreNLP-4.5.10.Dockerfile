FROM eclipse-temurin:21-jre-jammy

# The named BuildKit context is the verified 4.5.10 distribution, including
# the Chinese model JAR. Keeping it in the image makes the container offline.
COPY --from=corenlp_dist . /opt/corenlp

WORKDIR /opt/corenlp

EXPOSE 9000

ENTRYPOINT ["java", "-Xmx6g", "-cp", "*", "edu.stanford.nlp.pipeline.StanfordCoreNLPServer"]
CMD ["-serverProperties", "StanfordCoreNLP-chinese.properties", "-port", "9000", "-timeout", "120000"]
